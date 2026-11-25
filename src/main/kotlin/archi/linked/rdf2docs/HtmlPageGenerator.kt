package archi.linked.rdf2docs

import archi.linked.rdf2docs.notation.localName
import kotlinx.coroutines.*
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.slf4j.LoggerFactory
import java.io.File
import java.io.StringWriter
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object HtmlPageGenerator {

    private val log = LoggerFactory.getLogger(HtmlPageGenerator::class.java)

    /**
     * What a resource with a page is, for documentation purposes.
     *
     * A page is for a resource in its own right, chosen by what the triples say it is (DD-41): a view, a
     * relationship, an element, a model, a documentation section, a provenance record, and a former name kept so
     * that an IRI minted before a rename still opens something. A shape is a fragment of its view's page and a
     * part is shown on its owner's page, so neither has a kind, and a subject that is none of these gets no
     * page. [classify] has the rules.
     *
     * Declared most specific first: a resource that matches two kinds is the earlier one, so a BPMN process
     * that is its diagram's view is a view.
     */
    enum class ResourceKind { VIEW, RELATIONSHIP, ELEMENT, MODEL, DOCUMENTATION, PROVENANCE, FORMER_NAME }

    /** The kinds that make up the architecture itself: what the index's folders, the graph view and the AI context show. */
    val CONCEPT_KINDS = setOf(ResourceKind.VIEW, ResourceKind.RELATIONSHIP, ResourceKind.ELEMENT)

    data class SubjectInfo(
        val iri: String,
        val kind: ResourceKind,
        val types: MutableSet<String> = mutableSetOf(),
        val typeIris: MutableMap<String, String> = mutableMapOf(),
        var label: String? = null,
        var imageUrl: String? = null,
    )

    /** A resource that could not be written, and why, so a run can report it instead of aborting. */
    data class SkippedResource(val iri: String, val reason: String)

    /** What a run produced: the pages written, by kind, and the resources it could not write. */
    data class GenerationResult(
        val pagesGenerated: Int,
        val skipped: List<SkippedResource>,
        val pagesByKind: Map<ResourceKind, Int> = emptyMap(),
    )

    /** How many resources one coroutine handles, and therefore how many share a connection. */
    private const val BATCH_SIZE = 100

    /** How deep a part of a part is still drawn inline on its owner's page, before it is named instead. */
    private const val MAX_PART_NESTING = 2

    private const val SCHEMA = "https://schema.org/"
    private const val DCTERMS = "http://purl.org/dc/terms/"
    private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"

    /** Core classes that are views without being called one: `arch:Catalog` and `arch:Matrix` are `rdfs:subClassOf arch:View`. */
    private val VIEW_CLASSES = setOf("View", "Diagram", "Catalog", "Matrix")

    private data class FolderNode(val iri: String, val name: String, val children: MutableList<FolderNode> = mutableListOf(), val members: MutableList<SubjectInfo> = mutableListOf())

    /** One subject under the base as the subject query sees it, before [classify] decides whether it gets a page. */
    private class Observed(val iri: String) {
        val types = linkedSetOf<IRI>()
        var label: String? = null
        var name: String? = null
        var imageUrl: String? = null
        var isRelationship = false
        var isMember = false
        var isDocumentation = false
        var isFormerName = false
    }

    private lateinit var ns: Namespaces
    private lateinit var notation: NotationSupport

    // Read and written from every generation coroutine, so it cannot be a plain map. `getOrPut` on a
    // ConcurrentHashMap is not atomic — two threads can resolve the same label once each — but the
    // result is identical either way, which is cheaper than holding a lock across a SPARQL query.
    private val labelCache = ConcurrentHashMap<String, String>()

    /**
     * What a resource is called: its `skos:prefLabel`, else its `schema:name`, `rdfs:label` or `dcterms:title`,
     * else the last segment of its IRI. A model is the case for the names: its IRI ends in `/model` (DD-41), so
     * without them every model would be called "model".
     */
    private fun getLabelCached(conn: RepositoryConnection, iri: String): String {
        return labelCache.getOrPut(iri) {
            val query = """
                PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT ?label ?name ?rdfsLabel ?title WHERE {
                    OPTIONAL { <$iri> skos:prefLabel ?label }
                    OPTIONAL { <$iri> <${SCHEMA}name> ?name }
                    OPTIONAL { <$iri> rdfs:label ?rdfsLabel }
                    OPTIONAL { <$iri> <${DCTERMS}title> ?title }
                } LIMIT 1
            """.trimIndent()
            conn.prepareTupleQuery(query).evaluate().use { res ->
                val b = if (res.hasNext()) res.next() else null
                listOf("label", "name", "rdfsLabel", "title").firstNotNullOfOrNull { (b?.getValue(it) as? Literal)?.stringValue() }
                    ?: modelName(conn, iri)
                    ?: iri.substringAfterLast('/')
            }
        }
    }

    /**
     * A model's name when it states none: the last segment of the namespace it states,
     * `vann:preferredNamespaceUri`, which is the model's id. Null for anything that states no namespace.
     */
    private fun modelName(conn: RepositoryConnection, iri: String): String? {
        val vf = conn.valueFactory
        val ns = conn.getStatements(vf.createIRI(iri), vf.createIRI("http://purl.org/vocab/vann/", "preferredNamespaceUri"), null, true)
            .use { res -> res.firstOrNull()?.`object`?.stringValue() } ?: return null
        return ns.trimEnd('/', '#').substringAfterLast('/').takeIf { it.isNotEmpty() }
    }

    /**
     * Writes every page, the index, the graph page and the data pages into [outputDir].
     *
     * [presentationContext] is the audience the diagrams are drawn for when a page opens — an IRI, a local name or a
     * name of one of the model's languages' contexts; each view page offers the others too.
     */
    fun generate(repository: Repository, baseIri: String, outputDir: File, ns: Namespaces, graphConfig: GraphConfig = GraphConfig(),
                 presentationContext: String? = null): GenerationResult = runBlocking {
        this@HtmlPageGenerator.ns = ns
        labelCache.clear()
        ProvenanceSection.reset()

        SiteAssets.copyStatic(outputDir)
        val skipped = java.util.Collections.synchronizedList(mutableListOf<SkippedResource>())

        repository.connection.use { conn ->
            notation = NotationSupport(conn, outputDir, presentationContext)
            val (subjects, unpaged) = scanSubjects(conn, baseIri, ns)
            nameRelationships(conn, subjects)
            val site = Site(baseIri, subjects, Site.owners(unpaged, subjects, baseIri))

            // Generate pages in parallel batches.
            //
            // Each batch opens its own connection. An rdf4j connection is not thread-safe, and —
            // more importantly here — a connection shared with the caller is closed as soon as one
            // coroutine fails, which turns a single unwritable resource into "Connection has been
            // closed" on every job still in flight and leaves the output half-written.
            val jobs = subjects.values.chunked(BATCH_SIZE).map { batch ->
                async(Dispatchers.IO) {
                    repository.connection.use { batchConn ->
                        batch.forEach { info -> generateOrSkip(batchConn, info, site, outputDir, skipped) }
                    }
                }
            }
            jobs.awaitAll()

            TreeDataGenerator.generate(conn, notation, ns, site, subjects, outputDir)
            generateIndexPageWithConn(conn, subjects.values.toList(), site, outputDir, graphConfig)
            DataPagesGenerator.generate(conn, notation, site, subjects, outputDir, siteTitle(conn, subjects))
            val failures = skipped.toList()
            if (failures.isNotEmpty()) {
                log.warn("Skipped {} of {} resources; see generation-stats.md for the full list", failures.size, subjects.size)
            }
            val failed = failures.mapTo(HashSet()) { it.iri }
            val byKind = subjects.values.filter { it.iri !in failed }.groupingBy { it.kind }.eachCount().toSortedMap()
            // +1 for the index page.
            GenerationResult(subjects.size - failures.size + 1, failures, byKind)
        }
    }

    /**
     * Writes one resource, or records why it could not be written.
     *
     * One unrepresentable resource is not a reason to abandon the other 15,000: the run continues and
     * reports what it could not do. The check comes first so the common cause — an IRI segment longer
     * than the filesystem allows — is named precisely rather than surfacing as a bare
     * `FileNotFoundException (Filename too long)`; the catch covers everything else.
     */
    private fun generateOrSkip(
        conn: RepositoryConnection,
        info: SubjectInfo,
        site: Site,
        outputDir: File,
        skipped: MutableList<SkippedResource>,
    ) {
        val unwritable = PagePaths.unwritableReason(info.iri, site.baseIri)
        if (unwritable != null) {
            log.warn("Skipping <{}>: {}", info.iri, unwritable)
            skipped.add(SkippedResource(info.iri, unwritable))
            return
        }
        try {
            generateResourceDir(conn, info, site, outputDir)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            log.warn("Skipping <{}>: {}", info.iri, reason)
            skipped.add(SkippedResource(info.iri, reason))
        }
    }

    /**
     * A name for each page that has none of its own: an `rdfs:label` or a `dcterms:title` — a BPMN diagram's title —
     * and, for a relationship, its ends, "Customer → Place order", as the AI context names one. Only the pages'
     * names: the subject scan the AI context shares is left as it is.
     */
    private fun nameRelationships(conn: RepositoryConnection, subjects: Map<String, SubjectInfo>) {
        val unnamed = subjects.values.filter { it.label == null }
        if (unnamed.isEmpty()) return
        val titles = HashMap<String, String>()
        conn.prepareTupleQuery("""
            SELECT ?s ?l WHERE { { ?s <http://www.w3.org/2000/01/rdf-schema#label> ?l } UNION { ?s <${DCTERMS}title> ?l } }
        """.trimIndent()).evaluate().use { res -> res.forEach { b -> val s = b.getValue("s")?.stringValue(); val l = b.getValue("l")?.stringValue(); if (s != null && l != null) titles.putIfAbsent(s, l) } }
        val ends = HashMap<String, Pair<String, String>>()
        conn.prepareTupleQuery("""
            SELECT ?r ?s ?t WHERE { ?r <${ns.core}source> ?s ; <${ns.core}target> ?t }
        """.trimIndent()).evaluate().use { res -> res.forEach { b -> val r = b.getValue("r")?.stringValue() ?: return@forEach; ends.putIfAbsent(r, b.getValue("s").stringValue() to b.getValue("t").stringValue()) } }
        for (info in unnamed) {
            info.label = titles[info.iri] ?: if (info.kind == ResourceKind.RELATIONSHIP) ends[info.iri]?.let { (s, t) ->
                "${getLabelCached(conn, s)} → ${getLabelCached(conn, t)}"
            } else null
        }
    }

    /** The resources under [baseIri] that get a page, keyed and ordered by IRI. */
    fun querySubjects(conn: RepositoryConnection, baseIri: String, ns: Namespaces): Map<String, SubjectInfo> =
        scanSubjects(conn, baseIri, ns).first

    /**
     * Every IRI subject under [baseIri], split into those that get a page, with their kind, and the rest: the
     * shapes, parts, folders and anything else no rule pages.
     */
    private fun scanSubjects(conn: RepositoryConnection, baseIri: String, ns: Namespaces): Pair<Map<String, SubjectInfo>, List<String>> {
        // The subjects are found once, in the subquery, so that the EXISTS tests run per subject rather than
        // per statement.
        val query = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            PREFIX schema: <$SCHEMA>
            PREFIX dcterms: <$DCTERMS>
            PREFIX arch: <${ns.core}>
            SELECT ?subject ?type ?label ?name ?image ?isRel ?isMember ?isDoc ?isFormer WHERE {
                {
                    SELECT DISTINCT ?subject WHERE {
                        ?subject ?p ?o .
                        FILTER(isIRI(?subject))
                        FILTER(STRSTARTS(STR(?subject), "$baseIri"))
                    }
                }
                OPTIONAL { ?subject rdf:type ?type }
                OPTIONAL { ?subject skos:prefLabel ?label }
                # A name where there is no label: a documentation section's file, the converter of a run.
                OPTIONAL { ?subject schema:name ?name }
                OPTIONAL { ?subject schema:image ?image }
                # Endpoints rather than type: a relationship is typed with its own class
                # (uml:Message, a BPMN sequence flow), and whether arch:QualifiedRelationship is
                # also asserted on the instance depends on the converter. Having an arch:source
                # is what makes a resource a relationship in every notation.
                BIND(EXISTS { ?subject arch:source ?endpoint } AS ?isRel)
                # A member of a model, by its own statement or by a folder listing it.
                BIND((EXISTS { ?subject arch:inModel ?model }
                      || EXISTS { ?folder a arch:Folder ; schema:itemListElement ?entry . ?entry schema:item ?subject }
                      || EXISTS { ?subject dcterms:isPartOf ?container . ?container a arch:Folder }) AS ?isMember)
                BIND(EXISTS { ?about schema:subjectOf ?subject } AS ?isDoc)
                BIND(EXISTS { ?subject dcterms:isReplacedBy ?successor } AS ?isFormer)
            } ORDER BY ?subject
        """.trimIndent()
        val observed = linkedMapOf<String, Observed>()
        conn.prepareTupleQuery(query).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val iri = (b.getValue("subject") as? IRI)?.stringValue() ?: continue
                val o = observed.getOrPut(iri) { Observed(iri) }
                (b.getValue("type") as? IRI)?.let { o.types.add(it) }
                if (o.label == null) (b.getValue("label") as? Literal)?.stringValue()?.let { o.label = it }
                if (o.name == null) (b.getValue("name") as? Literal)?.stringValue()?.let { o.name = it }
                if (o.imageUrl == null) (b.getValue("image") as? Literal)?.stringValue()?.let { o.imageUrl = it }
                fun flag(name: String) = (b.getValue(name) as? Literal)?.booleanValue() == true
                o.isRelationship = o.isRelationship || flag("isRel")
                o.isMember = o.isMember || flag("isMember")
                o.isDocumentation = o.isDocumentation || flag("isDoc")
                o.isFormerName = o.isFormerName || flag("isFormer")
            }
        }

        val pages = linkedMapOf<String, SubjectInfo>()
        val unpaged = mutableListOf<String>()
        for (o in observed.values) {
            val kind = classify(o, ns)
            // A former name that is the base itself — a model's IRI from before DD-41, with --model-id — is the
            // index's URL already, and the index is the page to open there. Any other resource at the base is
            // skipped with its reason (PagePaths.unwritableReason).
            val formerNameAtBase = kind == ResourceKind.FORMER_NAME && PagePaths.relative(o.iri, baseIri).isEmpty()
            if (kind == null || formerNameAtBase) { unpaged.add(o.iri); continue }
            val label = o.label ?: o.name ?: if (kind == ResourceKind.MODEL) modelName(conn, o.iri) else null
            val info = SubjectInfo(o.iri, kind, label = label, imageUrl = o.imageUrl)
            // Generic types are shown only when nothing more specific is. A resource is typed
            // `arch:Element, arch:ModelConcept, uml:Class`, and every heading and type tag derived from the
            // first of them should read "Class", not "ModelConcept".
            val (generic, specific) = o.types.partition { t ->
                (t.namespace == ns.core && t.localName != "View" && t.localName != "Diagram") || t.namespace.startsWith("http://www.w3.org/")
            }
            (specific.ifEmpty { generic }).forEach { t ->
                info.types.add(t.localName)
                info.typeIris[t.localName] = t.stringValue()
            }
            pages[o.iri] = info
        }
        return pages to unpaged
    }

    /**
     * Whether a subject gets a page, and as what, or null when it does not.
     *
     * By what the triples say, never by the shape of the IRI:
     *
     * - a shape, a style, a point or anything else typed in core-vis, and a folder, never: a shape is a fragment
     *   of its view's page, and the folders are the index;
     * - a view: typed `arch:View`, `arch:Diagram`, `arch:Catalog` or `arch:Matrix`, or with a type called `View`
     *   or `Diagram` in any vocabulary;
     * - a relationship: typed `arch:QualifiedRelationship`, or with an `arch:source`;
     * - a model: typed `arch:Model`;
     * - an element: typed with any other core class, such as `arch:Element` or `arch:Stakeholder`, or a member of
     *   a model, by `arch:inModel` or by a folder that lists it;
     * - a documentation section: the object of `schema:subjectOf`;
     * - a provenance record: typed with a PROV class — a conversion run, the converter, a source file, a named
     *   graph (`prov:Bundle`), a qualified derivation;
     * - a former name: with a `dcterms:isReplacedBy`, as a model's IRI from before DD-41 is.
     *
     * Anything else is a part of a resource, such as a property pair, a tag or a folder entry, and is shown on
     * its owner's page ([Site.owners]).
     */
    private fun classify(o: Observed, ns: Namespaces): ResourceKind? {
        val types = o.types.map { it.stringValue() }
        if (types.any { it.startsWith(ns.coreVis) } || "${ns.core}Folder" in types) return null
        val core = types.filter { it.startsWith(ns.core) }.map { it.removePrefix(ns.core) }
        return when {
            core.any { it in VIEW_CLASSES } || o.types.any { it.localName == "View" || it.localName == "Diagram" } -> ResourceKind.VIEW
            "QualifiedRelationship" in core || o.isRelationship -> ResourceKind.RELATIONSHIP
            "Model" in core -> ResourceKind.MODEL
            core.isNotEmpty() || o.isMember -> ResourceKind.ELEMENT
            o.isDocumentation -> ResourceKind.DOCUMENTATION
            types.any { it.startsWith(ProvenanceSection.PROV) } -> ResourceKind.PROVENANCE
            o.isFormerName -> ResourceKind.FORMER_NAME
            else -> null
        }
    }

    // ── Pieces every page is made of (the header and the tree's panel are PageLayout's) ──────

    /** Is the concept a relationship, as far as drawing its type goes. */
    private fun isRelationship(info: SubjectInfo?) = info?.kind == ResourceKind.RELATIONSHIP

    /** A type's badge: its icon and its name, linking to the type's definition. */
    private fun typeBadge(look: TypeLook?, back: String): String {
        if (look == null) return ""
        return "<a class=\"type-badge\" href=\"${escapeHtml(look.iri)}\" target=\"_blank\" title=\"${escapeHtml(look.iri)}\">" +
            notation.looks.iconHtml(look, back) + escapeHtml(look.label) + "</a>"
    }

    /** A concept's name with its type's icon, linked to its page when it has one. */
    private fun conceptHtml(site: Site, iri: String, label: String, back: String, relationship: Boolean? = null): String {
        val kind = site.pages[iri]?.kind
        val rel = relationship ?: isRelationship(site.pages[iri])
        // A type's icon is for the architecture's concepts: a model, a record or a document has none.
        val look = if (kind == null || kind in CONCEPT_KINDS) notation.lookOf(iri, rel) else null
        val icon = look?.let { notation.looks.iconHtml(it, back) } ?: ""
        return "<span class=\"name-cell\">$icon${linkHtml(site, iri, label, back)}</span>"
    }

    private fun typeLabelOf(iri: String, rel: Boolean): String = notation.lookOf(iri, rel)?.label ?: ""

    private fun count(n: Int) = "<span class=\"count\">$n</span>"

    /** A collapsible group of rows: its summary, then its body. */
    private fun group(summary: String, body: String, open: Boolean = true, id: String? = null) =
        "<details class=\"group\"${if (open) " open" else ""}${id?.let { " id=\"$it\"" } ?: ""}><summary>$summary</summary><div class=\"group-body\">$body</div></details>"

    private fun sparqlLink(back: String, query: String) =
        "${back}sparql.html#query=" + URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20")

    private fun generateResourceDir(conn: RepositoryConnection, info: SubjectInfo, site: Site, outputDir: File) {
        val subjectIri = info.iri
        val baseIri = site.baseIri
        val vf = conn.valueFactory

        // The resource's own statements, once each: a TriG input can state one triple in several graphs.
        val allStmts = statementsAbout(conn, vf.createIRI(subjectIri))

        val label = info.label ?: subjectIri.substringAfterLast('/')
        val dir = File(outputDir, PagePaths.diskDir(subjectIri, baseIri))
        dir.mkdirs()

        val depth = PagePaths.depth(subjectIri, baseIri)
        val back = "../".repeat(depth)

        // facts.ttl: the resource, and what is shown on its page without a page of its own: its parts and, for a
        // view, its shapes. A shape is a fragment of the view's page, so the view's document describes it.
        val facts = allStmts + site.partsOf(subjectIri).flatMap { statementsAbout(conn, vf.createIRI(it)) }
        val ttlWriter = StringWriter()
        Rio.write(facts, ttlWriter, RDFFormat.TURTLE)
        File(dir, "facts.ttl").writeText(ttlWriter.toString())

        val hasPropPred = "${SCHEMA}additionalProperty"
        // The relationships an element takes part in are on its Relationships tab; their unqualified triples and
        // the qualified links to them are left out of its details.
        val mainStmts = allStmts.filter { stmt ->
            val localName = stmt.predicate.localName
            val isRelationship = localName.startsWith("qualified") ||
                localName in setOf("accesses", "realizes", "serves", "flows", "triggers", "influences", "assigned", "assignedTo", "composition", "aggregation", "specialization", "association")
            stmt.predicate.stringValue() != hasPropPred && !isRelationship
        }
        // A pair is a part with an IRI, `{owner}/property/{digest}`, from converters that write no blank nodes,
        // and a blank node from those that do.
        val pairs = allStmts.filter { it.predicate.stringValue() == hasPropPred }.mapNotNull { it.`object` as? Resource }

        data class Prop(val key: String, val value: String)
        val props = pairs.flatMap { pair ->
            val stmts = statementsAbout(conn, pair)
            val key = stmts.find { it.predicate.localName == "name" && it.predicate.namespace == SCHEMA }?.`object`?.stringValue() ?: return@flatMap emptyList()
            val value = stmts.find { it.predicate.localName == "value" && it.predicate.namespace == SCHEMA }?.`object`?.stringValue() ?: ""
            listOf(Prop(key, value))
        }.sortedBy { it.key }

        val mainRows = detailsRows(conn, site, mainStmts, back, subjectIri)
        val propRows = props.joinToString("") { p -> "            <tr><td>${escapeHtml(p.key)}</td><td>${escapeHtml(p.value)}</td></tr>\n" }

        val extraTabs = mutableListOf<Pair<String, String>>()
        val isView = info.kind == ResourceKind.VIEW

        if (propRows.isNotEmpty()) {
            extraTabs.add("Properties" to propRows)
        }

        if (info.kind in CONCEPT_KINDS) {
            // Add Views tab - but not for Views themselves
            if (!isView) {
                val viewsHtml = buildViewsSection(conn, site, subjectIri, back)
                if (viewsHtml.isNotEmpty()) {
                    extraTabs.add("Views" to viewsHtml)
                }
            }

            // Add Relationships tab for Elements, a BPMN process that is its own view included
            val relsHtml = buildRelationshipsSection(conn, site, subjectIri, back)
            if (relsHtml.isNotEmpty()) {
                extraTabs.add("Relationships" to relsHtml)
            }

            // Add Endpoints tab for QualifiedRelationships
            val endpointsHtml = buildEndpointsSection(conn, site, subjectIri, back)
            if (endpointsHtml.isNotEmpty()) {
                extraTabs.add("Endpoints" to endpointsHtml)
            }
        }

        // What a view shows, under names of their own: a process that is its view has relationships of its
        // own as well.
        var diagramHtml = ""
        if (isView) {
            val (elemsHtml, relsInViewHtml) = buildViewContentTabs(conn, site, subjectIri, back)
            if (elemsHtml.isNotEmpty()) {
                extraTabs.add("Elements in view" to elemsHtml)
            }
            // Always add the tab for Views, even if empty
            val relContent = if (relsInViewHtml.isNotEmpty()) relsInViewHtml else "<p class=\"empty\">No relationships found in this view.</p>"
            extraTabs.add("Relationships in view" to relContent)

            // The diagram is in the page, not an <img>: the inside of an image cannot be the target of a
            // fragment, and `{view}#node-{id}` has to open the page at the shape. diagram.svg is written too.
            val drawn = diagramSection(site, subjectIri, back, dir)
            diagramHtml = drawn.first
            diagramHtml += buildShapesSection(conn, site, subjectIri, back, drawn.second, drawnAny = drawn.second.isNotEmpty())
        }

        if (info.kind == ResourceKind.DOCUMENTATION) {
            val aboutHtml = buildAboutSection(conn, site, subjectIri, back)
            if (aboutHtml.isNotEmpty()) extraTabs.add("About" to aboutHtml)
        }

        when (info.kind) {
            ResourceKind.PROVENANCE -> {
                val references = ProvenanceSection.referencesHtml(conn, site, subjectIri, back)
                if (references.isNotEmpty()) extraTabs.add("Referenced by" to references)
            }
            ResourceKind.FORMER_NAME -> {}
            else -> {
                val provenance = ProvenanceSection.html(conn, site, subjectIri, back, ns)
                if (provenance.isNotEmpty()) extraTabs.add("Provenance" to provenance)
            }
        }

        val notice = if (info.kind == ResourceKind.FORMER_NAME) formerNameNotice(conn, site, subjectIri, back) else ""
        File(dir, "index.html").writeText(pageHtml(conn, info, label, subjectIri, back, mainRows, extraTabs, diagramHtml, notice, site))
    }

    /** The statements about [subject], one per triple whatever graphs state it. */
    private fun statementsAbout(conn: RepositoryConnection, subject: Resource): List<Statement> =
        conn.getStatements(subject, null, null, true).use { res -> res.toList() }
            .distinctBy { Triple(it.subject, it.predicate, it.`object`) }

    private fun predicateHtml(predicate: IRI) =
        "<a href=\"${escapeHtml(predicate.stringValue())}\" target=\"_blank\">${escapeHtml(predicate.localName)}</a>"

    /** A predicate's prefixed name, as the model's prefixes write it. */
    private fun prefixed(iri: String): String {
        val best = notation.prefixes.entries.filter { iri.startsWith(it.value) && it.value.isNotEmpty() }.maxByOrNull { it.value.length }
        return if (best != null) "${best.key}:${iri.removePrefix(best.value)}" else iri
    }

    /** A predicate's local name as words: `prefLabel` is "Pref label", `modelConformsToMetamodel` "Model conforms to metamodel". */
    private fun humanize(name: String): String {
        val words = name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim().lowercase()
        return words.replaceFirstChar { it.uppercase() }
    }

    /** What a predicate is called in the details table, where a plainer word says it better than its local name. */
    private val PREDICATE_NAMES = mapOf(
        RDF_TYPE to "Type", "http://www.w3.org/2004/02/skos/core#prefLabel" to "Name", "http://www.w3.org/2004/02/skos/core#altLabel" to "Also called",
        "http://www.w3.org/2004/02/skos/core#definition" to "Definition", "http://www.w3.org/2004/02/skos/core#notation" to "Identifier",
        "${SCHEMA}name" to "Name", "${SCHEMA}description" to "Description", "${DCTERMS}description" to "Description", "${DCTERMS}title" to "Title",
        "http://www.w3.org/2000/01/rdf-schema#label" to "Label", "http://www.w3.org/2000/01/rdf-schema#comment" to "Comment",
    )

    /** The order the details table lists predicates in: what it is, what it is called, what it says, then the rest. */
    private fun predicateRank(p: String) = when (p) {
        RDF_TYPE -> 0
        "http://www.w3.org/2004/02/skos/core#prefLabel", "${SCHEMA}name", "${DCTERMS}title" -> 1
        "http://www.w3.org/2004/02/skos/core#definition", "${SCHEMA}description", "${DCTERMS}description", "http://www.w3.org/2000/01/rdf-schema#comment" -> 2
        else -> 3
    }

    /**
     * The details table: one row a predicate, its values together; a type shown with its icon and its language's
     * name for it, a concept with a page by its icon and name, a language-tagged text with its language.
     */
    private fun detailsRows(conn: RepositoryConnection, site: Site, stmts: List<Statement>, back: String, page: String): String {
        val byPredicate = stmts.groupBy { it.predicate }
        val sb = StringBuilder()
        for ((predicate, list) in byPredicate.entries.sortedWith(compareBy({ predicateRank(it.key.stringValue()) }, { it.key.localName.lowercase() }))) {
            val p = predicate.stringValue()
            val name = PREDICATE_NAMES[p] ?: humanize(predicate.localName)
            val values = list.map { it.`object` }.sortedBy { it.stringValue() }.map { o ->
                if (p == RDF_TYPE && o is IRI) {
                    val type = o.stringValue()
                    val rel = site.pages[page]?.kind == ResourceKind.RELATIONSHIP
                    // A language's type, and a view, with its icon; the core's generic classes, which every element
                    // has, as names.
                    val generic = type.startsWith("http://www.w3.org/") || (type.startsWith(ns.core) && type.removePrefix(ns.core) !in VIEW_CLASSES)
                    val look = if (generic || !notation.pack.knows(type)) null else notation.looks.look(type, rel)
                    if (look != null) typeBadge(look, back)
                    else "<a href=\"${escapeHtml(type)}\" target=\"_blank\" title=\"${escapeHtml(type)}\">${escapeHtml(prefixed(type))}</a>"
                } else valueHtml(conn, site, o, predicate.localName, back, page)
            }
            val joined = if (values.size == 1) values[0] else values.joinToString("") { "<div>$it</div>" }
            sb.append("            <tr><td>${escapeHtml(name)}<span class=\"pred\"><a href=\"${escapeHtml(p)}\" target=\"_blank\">${escapeHtml(prefixed(p))}</a></span></td><td>$joined</td></tr>\n")
        }
        return sb.toString()
    }

    /**
     * A value as a cell of the page of [page].
     *
     * An IRI with a page is a link to it, and so is a shape, to its view's page at the shape: a BPMN view lists
     * its shapes with `planeElement`, and each links to the drawing. A part of this page — a tag, a
     * subscription, a decision's rationale — is drawn here, as the table of its own statements, since it has no
     * page to link to. An IRI under the base that nothing shows is written out, not linked: a link to it would
     * lead nowhere.
     */
    private fun valueHtml(conn: RepositoryConnection, site: Site, o: Value, predLocalName: String, back: String, page: String, nesting: Int = 0): String = when {
        o is IRI -> {
            val iri = o.stringValue()
            if (site.ownerOf(iri) == page && '#' !in iri && nesting < MAX_PART_NESTING) {
                partHtml(conn, site, o, back, page, nesting + 1)
            } else if (site.pages.containsKey(iri)) {
                // A concept with a page: its icon and its name, the IRI on hover.
                conceptHtml(site, iri, getLabelCached(conn, iri), back)
            } else {
                // What the IRI is called: under the base, its name (a folder's); outside it, its name in a loaded
                // language, else its prefixed name — the IRI itself on hover either way.
                val inSite = iri.startsWith(site.baseIri)
                // A shape: by what it draws, its fragment beside it.
                val drawn = if (inSite && '#' in iri) {
                    val s = archi.linked.rdf2docs.notation.iri(iri)
                    (notation.t.first(s, "${ns.coreVis}archElement") ?: notation.t.first(s, "${ns.coreVis}archRelationship")) as? IRI
                } else null
                val text = when {
                    drawn != null -> getLabelCached(conn, drawn.stringValue())
                    predLocalName in setOf("source", "target") || inSite -> getLabelCached(conn, iri)
                    else -> notation.pack.typeLabel(iri) ?: prefixed(iri)
                }
                val suffix = if (drawn != null) " <small>#${escapeHtml(iri.substringAfter('#'))}</small>" else ""
                val href = site.href(iri, back)
                if (href == null) "<span title=\"${escapeHtml(iri)}\">${escapeHtml(text)}</span>$suffix"
                else {
                    val target = if (inSite) "" else " target=\"_blank\""
                    "<a href=\"${escapeHtml(href)}\"$target title=\"${escapeHtml(iri)}\">${escapeHtml(text)}</a>$suffix"
                }
            }
        }
        o is Literal && predLocalName == "image" -> {
            val url = o.stringValue()
            "<a href=\"${escapeHtml(url)}\" target=\"_blank\"><img src=\"${escapeHtml(url)}\" alt=\"diagram\" style=\"max-width:100%;border:1px solid #ddd;\"></a>"
        }
        o is Literal && predLocalName == "archiUrl" -> {
            val url = o.stringValue()
            "<a href=\"${escapeHtml(url)}\" target=\"_blank\">${escapeHtml(url)}</a>"
        }
        // Documentation, a rationale: prose whose line breaks are part of it.
        o is Literal && '\n' in o.stringValue() -> "<div class=\"text\">${escapeHtml(o.stringValue())}</div>${languageTag(o)}"
        o is Literal -> escapeHtml(o.stringValue()) + languageTag(o)
        else -> escapeHtml(o.stringValue())
    }

    private fun languageTag(o: Literal): String = o.language.map { " <small>@$it</small>" }.orElse("")

    /** A part of the page's resource, as the table of its own statements. */
    private fun partHtml(conn: RepositoryConnection, site: Site, part: IRI, back: String, page: String, nesting: Int): String {
        val stmts = statementsAbout(conn, part)
        if (stmts.isEmpty()) return escapeHtml(part.stringValue())
        val rows = stmts.joinToString("") { s ->
            "<tr><td>${predicateHtml(s.predicate)}</td><td>${valueHtml(conn, site, s.`object`, s.predicate.localName, back, page, nesting)}</td></tr>"
        }
        return "<table class=\"part\" title=\"${escapeHtml(part.stringValue())}\"><tbody>$rows</tbody></table>"
    }

    /** For a former name: what replaced it, at the top of the page, so that an old link still gets somewhere. */
    private fun formerNameNotice(conn: RepositoryConnection, site: Site, iri: String, back: String): String {
        val successors = conn.getStatements(conn.valueFactory.createIRI(iri), conn.valueFactory.createIRI(DCTERMS, "isReplacedBy"), null, true)
            .use { res -> res.mapNotNull { (it.`object` as? IRI)?.stringValue() } }.distinct().sorted()
        if (successors.isEmpty()) return ""
        val links = successors.joinToString(", ") { s ->
            val href = site.href(s, back)
            if (href == null) escapeHtml(s) else "<a href=\"${escapeHtml(href)}\">${escapeHtml(getLabelCached(conn, s))}</a>"
        }
        return """
    <p class="notice">This IRI is a former name, replaced by $links.</p>"""
    }

    // Finds all arch:View instances that contain this resource as a visible element or relationship.
    // Each links to the view's page at the shape that shows the resource, `#node-{id}` or `#link-{id}`;
    // where it is shown twice, at the first.
    private fun buildViewsSection(conn: RepositoryConnection, site: Site, subjectIri: String, back: String): String {
        val query = """
            PREFIX archvis: <${ns.coreVis}>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            SELECT ?view (SAMPLE(?viewLabel) AS ?label) (MIN(STR(?node)) AS ?shape) (COUNT(DISTINCT ?node) AS ?times) WHERE {
                { ?node archvis:archElement <$subjectIri> ; archvis:view ?view }
                UNION
                { ?node archvis:archRelationship <$subjectIri> ; archvis:view ?view }
                OPTIONAL { ?view skos:prefLabel ?viewLabel }
            } GROUP BY ?view ORDER BY ?label
        """.trimIndent()
        data class Shown(val view: String, val label: String, val shape: String?, val times: Int)
        val items = mutableListOf<Shown>()
        conn.prepareTupleQuery(query).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val viewIri = (b.getValue("view") as? IRI)?.stringValue() ?: continue
                val lbl = (b.getValue("label") as? Literal)?.stringValue() ?: viewIri.substringAfterLast('/')
                items.add(Shown(viewIri, lbl, (b.getValue("shape") as? Literal)?.stringValue(), (b.getValue("times") as? Literal)?.intValue() ?: 1))
            }
        }
        if (items.isEmpty()) return ""
        val rows = items.joinToString("\n") { item ->
            val href = item.shape?.let { site.href(it, back) } ?: site.href(item.view, back)
            val icon = notation.lookOf(item.view, false)?.let { notation.looks.iconHtml(it, back) } ?: ""
            val name = if (href == null) escapeHtml(item.label) else "<a href=\"${escapeHtml(href)}\">${escapeHtml(item.label)}</a>"
            "            <tr><td><span class=\"name-cell\">$icon$name</span></td><td>${item.times}</td><td class=\"iri-cell\">${escapeHtml(item.view)}</td></tr>"
        }
        return """
    <table class="sortable"><thead><tr><th>View</th><th>Shapes</th><th>IRI</th></tr></thead>
    <tbody>
$rows
    </tbody></table>"""
    }

    /** A link to [iri] with [text], or the text alone when nothing in the site shows it. */
    private fun linkHtml(site: Site, iri: String, text: String, back: String): String {
        val href = site.href(iri, back) ?: return escapeHtml(text)
        val target = if (iri.startsWith(site.baseIri)) "" else " target=\"_blank\""
        return "<a href=\"${escapeHtml(href)}\"$target title=\"${escapeHtml(iri)}\">${escapeHtml(text)}</a>"
    }

    // For a concept: every relationship — whatever has an arch:source and an arch:target, as a page is classified,
    // since la-gram states only the language's type (am4:Serving) — where it is arch:source (outgoing) or
    // arch:target (incoming), and the other endpoint. Grouped by direction, each
    // group a sortable table: the relationship with its type's icon, and the element at its other end with its own.
    private fun buildRelationshipsSection(conn: RepositoryConnection, site: Site, subjectIri: String, back: String): String {
        val query = """
            PREFIX arch: <${ns.core}>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            SELECT DISTINCT ?rel ?relLabel ?other ?otherLabel ?dir WHERE {
                { ?rel arch:source <$subjectIri> ; arch:target ?anyTarget .
                  OPTIONAL { ?rel skos:prefLabel ?relLabel }
                  OPTIONAL { ?rel arch:target ?other . OPTIONAL { ?other skos:prefLabel ?otherLabel } }
                  BIND("outgoing" AS ?dir) }
                UNION
                { ?rel arch:target <$subjectIri> ; arch:source ?anySource .
                  OPTIONAL { ?rel skos:prefLabel ?relLabel }
                  OPTIONAL { ?rel arch:source ?other . OPTIONAL { ?other skos:prefLabel ?otherLabel } }
                  BIND("incoming" AS ?dir) }
            } ORDER BY ?dir ?relLabel
        """.trimIndent()
        data class Row(val relIri: String, val relLabel: String?, val dir: String, val otherIri: String?, val otherLabel: String?)
        val items = mutableListOf<Row>()
        conn.prepareTupleQuery(query).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val relIri = (b.getValue("rel") as? IRI)?.stringValue() ?: continue
                val otherIri = (b.getValue("other") as? IRI)?.stringValue()
                items.add(Row(relIri, (b.getValue("relLabel") as? Literal)?.stringValue(), (b.getValue("dir") as? Literal)?.stringValue() ?: "",
                    otherIri, (b.getValue("otherLabel") as? Literal)?.stringValue() ?: otherIri?.let { getLabelCached(conn, it) }))
            }
        }
        val distinct = items.distinctBy { it.relIri to it.dir }
        if (distinct.isEmpty()) return ""
        fun table(rows: List<Row>, otherHeading: String): String {
            val body = rows.sortedWith(compareBy({ typeLabelOf(it.relIri, true) }, { it.otherLabel ?: "" })).joinToString("\n") { r ->
                val relLook = notation.lookOf(r.relIri, true)
                val relType = relLook?.let { notation.looks.iconHtml(it, back) + escapeHtml(it.label) } ?: ""
                val relName = r.relLabel?.let { " · " + linkHtml(site, r.relIri, it, back) } ?: " · " + linkHtml(site, r.relIri, "open", back)
                val other = if (r.otherIri != null) conceptHtml(site, r.otherIri, r.otherLabel ?: r.otherIri, back) else ""
                val otherType = r.otherIri?.let { typeLabelOf(it, false) } ?: ""
                "            <tr data-row-type=\"${escapeHtml(relLook?.iri ?: "")}\"><td data-sort=\"${escapeHtml(relLook?.label ?: "")}\"><span class=\"name-cell\">$relType$relName</span></td><td data-sort=\"${escapeHtml(r.otherLabel ?: "")}\">$other</td><td>${escapeHtml(otherType)}</td></tr>"
            }
            return "<table class=\"sortable\"><thead><tr><th>Relationship</th><th>$otherHeading</th><th>Type</th></tr></thead><tbody>\n$body\n</tbody></table>"
        }
        val out = distinct.filter { it.dir == "outgoing" }
        val inc = distinct.filter { it.dir == "incoming" }
        val filterId = "rels-" + Integer.toHexString(subjectIri.hashCode())
        return buildString {
            append("<div class=\"toolbar\"><input type=\"search\" placeholder=\"Filter relationships…\" data-filter=\"#$filterId\"></div><div id=\"$filterId\">")
            if (out.isNotEmpty()) append(group("Outgoing ${count(out.size)} <span class=\"dir\">this → other</span>", table(out, "Target")))
            if (inc.isNotEmpty()) append(group("Incoming ${count(inc.size)} <span class=\"dir\">other → this</span>", table(inc, "Source")))
            append("</div>")
        }
    }

    // For a relationship — whatever has an arch:source or an arch:target: resolves its endpoints.
    private fun buildEndpointsSection(conn: RepositoryConnection, site: Site, subjectIri: String, back: String): String {
        val query = """
            PREFIX arch: <${ns.core}>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            SELECT ?src ?srcLabel ?tgt ?tgtLabel WHERE {
                FILTER(EXISTS { <$subjectIri> arch:source ?s } || EXISTS { <$subjectIri> arch:target ?t })
                OPTIONAL { <$subjectIri> arch:source ?src . OPTIONAL { ?src skos:prefLabel ?srcLabel } }
                OPTIONAL { <$subjectIri> arch:target ?tgt . OPTIONAL { ?tgt skos:prefLabel ?tgtLabel } }
            }
        """.trimIndent()
        var srcIri: String? = null; var srcLabel: String? = null
        var tgtIri: String? = null; var tgtLabel: String? = null
        conn.prepareTupleQuery(query).evaluate().use { res ->
            if (res.hasNext()) {
                val b = res.next()
                srcIri = (b.getValue("src") as? IRI)?.stringValue()
                srcLabel = (b.getValue("srcLabel") as? Literal)?.stringValue()
                tgtIri = (b.getValue("tgt") as? IRI)?.stringValue()
                tgtLabel = (b.getValue("tgtLabel") as? Literal)?.stringValue()
            }
        }
        if (srcIri == null && tgtIri == null) return ""
        fun cell(iri: String?, lbl: String?) = if (iri == null) "" else conceptHtml(site, iri, lbl ?: getLabelCached(conn, iri), back)
        fun type(iri: String?) = iri?.let { typeLabelOf(it, false) } ?: ""
        val relLook = notation.lookOf(subjectIri, true)
        val line = relLook?.let { "<span class=\"big-icon\">${it.iconHtml}</span>" } ?: "→"
        return """
    <table><thead><tr><th>Role</th><th>Element</th><th>Type</th></tr></thead>
    <tbody>
            <tr><td>source</td><td>${cell(srcIri, srcLabel)}</td><td>${escapeHtml(type(srcIri))}</td></tr>
            <tr><td>target</td><td>${cell(tgtIri, tgtLabel)}</td><td>${escapeHtml(type(tgtIri))}</td></tr>
    </tbody></table>
    <p class="faint">${cell(srcIri, srcLabel)} $line ${cell(tgtIri, tgtLabel)}</p>"""
    }

    // For arch:View resources: returns (elementsTabHtml, relationshipsTabHtml) as separate tab contents.
    // Elements via archvis:archElement, grouped by their type's group (a layer, a domain) and then by type;
    // relationships via archvis:archRelationship with source→rel→target, each with its type's icon.
    private fun buildViewContentTabs(conn: RepositoryConnection, site: Site, subjectIri: String, back: String): Pair<String, String> {
        val elemQuery = """
            PREFIX archvis: <${ns.coreVis}>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            SELECT DISTINCT ?elem ?label WHERE {
                ?node archvis:view <$subjectIri> ; archvis:archElement ?elem .
                OPTIONAL { ?elem skos:prefLabel ?label }
            } ORDER BY ?label
        """.trimIndent()
        data class Elem(val iri: String, val label: String, val look: TypeLook?)
        val elems = mutableListOf<Elem>()
        conn.prepareTupleQuery(elemQuery).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val iri = (b.getValue("elem") as? IRI)?.stringValue() ?: continue
                elems.add(Elem(iri, (b.getValue("label") as? Literal)?.stringValue() ?: iri.substringAfterLast('/'), notation.lookOf(iri, false)))
            }
        }
        val elemsByIri = elems.distinctBy { it.iri }

        val relQuery = """
            PREFIX archvis: <${ns.coreVis}>
            PREFIX arch: <${ns.core}>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            SELECT DISTINCT ?rel ?relLabel ?src ?srcLabel ?tgt ?tgtLabel WHERE {
                ?link archvis:view <$subjectIri> ; archvis:archRelationship ?rel .
                OPTIONAL { ?rel skos:prefLabel ?relLabel }
                OPTIONAL { ?rel arch:source ?src . OPTIONAL { ?src skos:prefLabel ?srcLabel } }
                OPTIONAL { ?rel arch:target ?tgt . OPTIONAL { ?tgt skos:prefLabel ?tgtLabel } }
            } ORDER BY ?srcLabel ?relLabel
        """.trimIndent()
        val relRows = mutableListOf<String>()
        val seen = HashSet<String>()
        conn.prepareTupleQuery(relQuery).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val relIri = (b.getValue("rel") as? IRI)?.stringValue() ?: continue
                if (!seen.add(relIri)) continue
                val relLook = notation.lookOf(relIri, true)
                val srcIri = (b.getValue("src") as? IRI)?.stringValue()
                val srcLbl = (b.getValue("srcLabel") as? Literal)?.stringValue() ?: srcIri?.let { getLabelCached(conn, it) } ?: ""
                val tgtIri = (b.getValue("tgt") as? IRI)?.stringValue()
                val tgtLbl = (b.getValue("tgtLabel") as? Literal)?.stringValue() ?: tgtIri?.let { getLabelCached(conn, it) } ?: ""
                val relName = (b.getValue("relLabel") as? Literal)?.stringValue()
                val relCell = (relLook?.let { notation.looks.iconHtml(it, back) } ?: "") +
                    linkHtml(site, relIri, relName ?: relLook?.label ?: relIri.substringAfterLast('/'), back) +
                    (if (relName != null && relLook != null) " <small>${escapeHtml(relLook.label)}</small>" else "")
                val srcCell = if (srcIri != null) conceptHtml(site, srcIri, srcLbl, back) else escapeHtml(srcLbl)
                val tgtCell = if (tgtIri != null) conceptHtml(site, tgtIri, tgtLbl, back) else escapeHtml(tgtLbl)
                relRows.add("            <tr data-row-type=\"${escapeHtml(relLook?.iri ?: "")}\"><td data-sort=\"${escapeHtml(srcLbl)}\">$srcCell</td><td data-sort=\"${escapeHtml(relLook?.label ?: "")}\"><span class=\"name-cell\">$relCell</span></td><td data-sort=\"${escapeHtml(tgtLbl)}\">$tgtCell</td></tr>")
            }
        }

        val elemsHtml = if (elemsByIri.isEmpty()) "" else buildString {
            val id = "view-elems"
            append("<div class=\"toolbar\"><input type=\"search\" placeholder=\"Filter elements…\" data-filter=\"#$id\">")
            append("<button class=\"btn\" data-expand=\"#$id\">Expand all</button><button class=\"btn\" data-collapse=\"#$id\">Collapse all</button></div><div id=\"$id\">")
            val byGroup = elemsByIri.groupBy { it.look?.group }
            for ((g, inGroup) in byGroup.entries.sortedWith(compareBy({ it.key?.order ?: Int.MAX_VALUE }, { it.key?.label ?: "~" }))) {
                val inner = inGroup.groupBy { it.look }.entries.sortedBy { it.key?.label ?: "~" }.joinToString("") { (look, items) ->
                    val rows = items.sortedBy { it.label.lowercase() }.joinToString("\n") { e ->
                        "<tr><td>${conceptHtml(site, e.iri, e.label, back, false)}</td><td class=\"iri-cell\">${escapeHtml(e.iri)}</td></tr>"
                    }
                    val title = (look?.let { notation.looks.iconHtml(it, back) + escapeHtml(it.label) } ?: "Untyped") + count(items.size)
                    group(title, "<table class=\"sortable\"><thead><tr><th>Element</th><th>IRI</th></tr></thead><tbody>\n$rows\n</tbody></table>")
                }
                if (byGroup.size == 1) append(inner) else append(group(escapeHtml(g?.label ?: "Other") + count(inGroup.size), inner))
            }
            append("</div>")
        }
        val relsHtml = if (relRows.isEmpty()) "" else
            """<div class="toolbar"><input type="search" placeholder="Filter relationships…" data-filter="#view-rels"></div><div id="view-rels"><table class="sortable"><thead><tr><th>Source</th><th>Relationship</th><th>Target</th></tr></thead><tbody>
${relRows.joinToString("\n")}
        </tbody></table></div>"""
        return elemsHtml to relsHtml
    }

    /**
     * The view's drawing, in its notation, with a toolbar: the presentation context it is drawn for, zoom, the names
     * on its lines, and the SVG to download; and a legend of the types it shows. The drawing for the site's default
     * context carries the shapes' fragments as ids; each other context that draws the view differently is a second
     * drawing on the page, shown when chosen. Returns the section and the shapes drawn.
     */
    private fun diagramSection(site: Site, viewIri: String, back: String, dir: File): Pair<String, Set<String>> {
        val r = notation.renderer
        if (!r.hasLayout(viewIri)) return "" to emptySet()
        val hrefOf = { iri: String -> site.href(iri, back) }
        val primary = r.render(viewIri, notation.defaultContext, back, hrefOf, withIds = true, idPrefix = "d0-") ?: return "" to emptySet()
        File(dir, "diagram.svg").writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n${primary.svg}")

        // The other contexts: a drawing for each that draws the view differently, else the one it draws alike.
        val variants = mutableListOf(primary.svg)
        val normalised = mutableListOf(normalise(primary.svg))
        val options = mutableListOf<Triple<String, String, Int>>()
        val choices = listOf<Pair<String?, String>>(null to notation.defaultLabel) + notation.contexts.map { it.iri to it.label }
        for ((context, label) in choices) {
            val index = if (context == notation.defaultContext) 0 else {
                val drawn = r.render(viewIri, context, back, hrefOf, withIds = false, idPrefix = "d${variants.size}-")
                val key = drawn?.let { normalise(it.svg) }
                val same = key?.let { normalised.indexOf(it) } ?: 0
                if (drawn == null || same >= 0) maxOf(same, 0) else {
                    variants.add(drawn.svg)
                    normalised.add(key!!)
                    variants.size - 1
                }
            }
            options.add(Triple(context ?: "", label, index))
        }
        val select = if (notation.contexts.isEmpty()) "" else buildString {
            append("<label>Presentation <select class=\"context\" title=\"The audience the view is drawn for\">")
            for ((value, label, index) in options) {
                val selected = if (value == (notation.defaultContext ?: "")) " selected" else ""
                append("<option value=\"${escapeHtml(value)}\" data-variant=\"$index\"$selected>${escapeHtml(label)}</option>")
            }
            append("</select></label>")
        }
        // The other drawings start hidden; the script shows one, by class, when its context is chosen.
        val svgs = variants.mapIndexed { i, s -> if (i == 0) s else s.replaceFirst("class=\"la-diagram\"", "class=\"la-diagram hidden\"") }

        val legend = legendHtml(viewIri, back)
        val html = """
    <h2>Diagram</h2>
    <div class="diagram-wrap">
      <div class="diagram-bar">$select
        <span class="seg"><button data-zoom="out" title="Zoom out (−)">−</button><button data-zoom="fit" title="The whole view (0)">Fit</button><button data-zoom="1" title="Actual size (1)">1:1</button><button data-zoom="in" title="Zoom in (+)">+</button></span>
        <span class="zoom">100%</span>
        <button class="btn" data-zoom="full" title="Full screen">⛶</button>
        <label><input type="checkbox" class="names"> Names on lines</label>
        <span class="spacer" style="flex:1"></span>
        <button class="btn" data-download>Download SVG</button>
        <a href="diagram.svg">diagram.svg</a>
      </div>
      <div class="diagram" title="Scroll or pinch to zoom, drag to pan; click a shape to open its page">
${svgs.joinToString("\n")}
        <span class="diagram-hint">scroll to zoom · drag to pan</span>
      </div>$legend
    </div>"""
        return html to primary.drawn
    }

    /** A drawing without what differs between two drawings of the same thing: its context and its definitions' ids. */
    private fun normalise(svg: String) = svg.replace(Regex(" data-context=\"[^\"]*\""), "").replace(Regex("\\bd\\d+-la-"), "la-")
        .replace(Regex(" (?:id|data-frag)=\"[^\"]*\""), "")

    /** The types a view shows, each with its icon: clicking one points out its shapes in the drawing. */
    private fun legendHtml(viewIri: String, back: String): String {
        val vis = notation.t
        val concepts = vis.subjects(archi.linked.rdf2docs.notation.NS.VIS + "view", archi.linked.rdf2docs.notation.iri(viewIri)).mapNotNull { s ->
            (vis.first(s, archi.linked.rdf2docs.notation.NS.VIS + "archElement") as? IRI)?.let { it.stringValue() to false }
                ?: (vis.first(s, archi.linked.rdf2docs.notation.NS.VIS + "archRelationship") as? IRI)?.let { it.stringValue() to true }
        }
        val looks = concepts.mapNotNull { (iri, rel) -> notation.lookOf(iri, rel) }.groupingBy { it }.eachCount()
        if (looks.isEmpty()) return ""
        val items = looks.entries.sortedWith(compareBy({ it.key.relationship }, { it.key.group?.order ?: Int.MAX_VALUE }, { it.key.label })).joinToString("") { (look, n) ->
            "<span data-type=\"${escapeHtml(look.iri)}\" title=\"Point out the ${escapeHtml(look.label)} shapes\">${look.iconHtml}${escapeHtml(look.label)} <small>$n</small></span>"
        }
        return "\n      <div class=\"legend\">$items</div>"
    }

    /**
     * The shapes of a view that the diagram does not draw, each a row whose `id` is its fragment, so that
     * `{view}#node-{id}` still has a target. A view whose source has no layout — PlantUML, Structurizr — has
     * no diagram at all, and every shape is listed; a drawn view lists only a shape it could not place.
     */
    private fun buildShapesSection(conn: RepositoryConnection, site: Site, viewIri: String, back: String, drawn: Set<String>, drawnAny: Boolean): String {
        val query = """
            PREFIX archvis: <${ns.coreVis}>
            PREFIX arch: <${ns.core}>
            SELECT ?shape ?concept ?isLink ?source ?target WHERE {
                ?shape archvis:view <$viewIri> .
                OPTIONAL { ?shape archvis:archElement ?element }
                OPTIONAL { ?shape archvis:archRelationship ?relationship .
                    OPTIONAL { ?relationship arch:source ?source }
                    OPTIONAL { ?relationship arch:target ?target } }
                BIND(COALESCE(?element, ?relationship) AS ?concept)
                BIND(EXISTS { ?shape archvis:source ?from } || EXISTS { ?shape a archvis:Link } AS ?isLink)
            } ORDER BY ?isLink ?shape
        """.trimIndent()
        val rows = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        conn.prepareTupleQuery(query).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val shape = (b.getValue("shape") as? IRI)?.stringValue() ?: continue
                if (shape in drawn || !seen.add(shape)) continue
                val fragment = if (shape.startsWith("$viewIri#")) shape.substring(viewIri.length + 1) else null
                val concept = (b.getValue("concept") as? IRI)?.stringValue()
                val shows = if (concept == null) "" else buildString {
                    append(conceptHtml(site, concept, getLabelCached(conn, concept), back))
                    val source = (b.getValue("source") as? IRI)?.stringValue()
                    val target = (b.getValue("target") as? IRI)?.stringValue()
                    if (source != null && target != null) {
                        append(" <small>").append(escapeHtml(getLabelCached(conn, source))).append(" → ")
                            .append(escapeHtml(getLabelCached(conn, target))).append("</small>")
                    }
                }
                val id = fragment?.let { " id=\"${escapeHtml(it)}\"" } ?: ""
                rows.add("            <tr$id><td>${escapeHtml(fragment ?: shape)}</td><td>$shows</td></tr>")
            }
        }
        if (rows.isEmpty()) return ""
        val heading = if (drawnAny) "<h2>Shapes not drawn</h2>\n    <p>These shapes have no place in the diagram above.</p>"
            else "<h2>Shapes</h2>\n    <p>This view has no layout to draw, so its shapes are listed.</p>"
        return """
    $heading
    <table class="sortable"><thead><tr><th>Shape</th><th>Shows</th></tr></thead>
    <tbody>
${rows.joinToString("\n")}
    </tbody></table>"""
    }

    /** For a documentation section: what it documents, the resources whose `schema:subjectOf` it is. */
    private fun buildAboutSection(conn: RepositoryConnection, site: Site, docIri: String, back: String): String {
        val owners = conn.getStatements(null, conn.valueFactory.createIRI(SCHEMA, "subjectOf"), conn.valueFactory.createIRI(docIri), true)
            .use { res -> res.mapNotNull { (it.subject as? IRI)?.stringValue() } }.distinct().sorted()
        if (owners.isEmpty()) return ""
        val rows = owners.joinToString("\n") { o ->
            "            <tr><td>${conceptHtml(site, o, getLabelCached(conn, o), back)}</td><td class=\"iri-cell\">${escapeHtml(o)}</td></tr>"
        }
        return """
    <table class="sortable"><thead><tr><th>Documents</th><th>IRI</th></tr></thead>
    <tbody>
$rows
    </tbody></table>"""
    }

    private val KIND_NAMES = mapOf(
        ResourceKind.VIEW to "View", ResourceKind.RELATIONSHIP to "Relationship", ResourceKind.ELEMENT to "Element", ResourceKind.MODEL to "Model",
        ResourceKind.DOCUMENTATION to "Documentation", ResourceKind.PROVENANCE to "Provenance", ResourceKind.FORMER_NAME to "Former name",
    )

    /** The model a resource is in, by `arch:inModel`, for the breadcrumb. */
    private fun modelOf(conn: RepositoryConnection, iri: String): String? =
        conn.getStatements(conn.valueFactory.createIRI(iri), conn.valueFactory.createIRI(ns.core, "inModel"), null, true)
            .use { res -> res.firstOrNull()?.`object` as? IRI }?.stringValue()

    private fun pageHtml(conn: RepositoryConnection, info: SubjectInfo, label: String, iri: String, back: String, mainRows: String,
                         extraTabs: List<Pair<String, String>> = emptyList(), diagramHtml: String = "", notice: String = "", site: Site): String {
        val sb = StringBuilder(2048)
        extraTabs.forEachIndexed { i, (name, content) ->
            val active = if (i == 0) " active" else ""
            // How many rows a list tab holds: its rows, less its tables' header rows.
            val rows = Regex("<tr[ >]").findAll(content).count() - Regex("<thead><tr").findAll(content).count()
            val n = if (name == "Properties" || name.endsWith("view") || name == "Relationships" || name == "Views") rows else 0
            sb.append("        <button class=\"tab-btn").append(active)
              .append("\" onclick=\"showTab(this,'tab-").append(i).append("')\">").append(name).append(if (n > 0) " ($n)" else "").append("</button>\n")
        }
        val tabButtons = sb.toString()

        sb.clear()
        extraTabs.forEachIndexed { i, (name, content) ->
            val display = if (i == 0) "" else " style=\"display:none\""
            val body = if (name == "Properties") """<table class="sortable"><thead><tr><th>Key</th><th>Value</th></tr></thead><tbody>
$content
    </tbody></table>""" else content
            sb.append("    <div id=\"tab-").append(i).append("\" class=\"tab-panel\"").append(display).append(">").append(body).append("</div>\n")
        }
        val tabPanels = sb.toString()
        val tabsBlock = if (extraTabs.isEmpty()) "" else """
    <div class="tabs">
$tabButtons
    </div>
$tabPanels"""
        val mainTable = if (mainRows.isBlank()) "" else """
    <h2>Details</h2>
    <table class="details-table"><tbody>
$mainRows
    </tbody></table>"""
        val rel = info.kind == ResourceKind.RELATIONSHIP
        val look = if (info.kind in CONCEPT_KINDS) notation.lookOf(iri, rel) else null
        val icon = look?.let { "<span class=\"h1-icon\">${it.iconHtml}</span>" } ?: ""
        val badge = look?.let { typeBadge(it, back) } ?: "<span class=\"type-badge\">${KIND_NAMES[info.kind]}</span>"
        val groupBadge = look?.group?.let { " <span class=\"group-badge\">${escapeHtml(it.label)}</span>" } ?: ""
        val model = modelOf(conn, iri)?.takeIf { it != iri }
        val crumb = buildString {
            append("<a href=\"${back}index.html\">Index</a>")
            if (model != null) append(" › ").append(linkHtml(site, model, getLabelCached(conn, model), back))
            append(" › ").append(KIND_NAMES[info.kind])
        }
        val describe = if (info.kind in CONCEPT_KINDS || info.kind == ResourceKind.MODEL)
            " <a href=\"${escapeHtml(sparqlLink(back, "DESCRIBE <$iri>"))}\">Query in SPARQL</a>" else ""
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${escapeHtml(label)}</title>
    <link rel="stylesheet" href="${back}style.css">
</head>
${PageLayout.body(back, iri)}
${PageLayout.header(back, "", tree = true)}
<div class="shell">
${PageLayout.TREE_PANEL}
<main class="page">
    <div class="breadcrumb">$crumb</div>
    <h1>$icon${escapeHtml(label)} $badge$groupBadge</h1>
    <p class="iri"><span>${escapeHtml(iri)}</span><button class="copy" data-copy="${escapeHtml(iri)}" title="Copy the IRI">copy</button></p>$notice
    <p class="meta-links">📄 <a href="facts.ttl">facts.ttl</a>$describe</p>
$mainTable
$diagramHtml
$tabsBlock
</main>
</div>
${PageLayout.scripts(back, *(if (diagramHtml.contains("diagram-wrap")) arrayOf("${SiteAssets.DIR}/js/diagram.js") else emptyArray()))}
</body></html>""".trimIndent()
    }

    private fun queryFolderTree(conn: RepositoryConnection, baseIri: String, subjects: Map<String, SubjectInfo>): List<FolderNode> {
        // 1. Load all folders
        val folderQuery = """
            PREFIX arch: <${ns.core}>
            PREFIX schema: <https://schema.org/>
            PREFIX dcterms: <http://purl.org/dc/terms/>
            SELECT ?folder ?name ?parent WHERE {
                ?folder a arch:Folder .
                OPTIONAL { ?folder schema:name ?name }
                OPTIONAL { ?folder dcterms:isPartOf ?parent . ?parent a arch:Folder }
            }
        """.trimIndent()
        val folderMap = mutableMapOf<String, FolderNode>()
        val parentOf = mutableMapOf<String, String>() // child iri -> parent iri
        conn.prepareTupleQuery(folderQuery).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val iri = (b.getValue("folder") as? IRI)?.stringValue() ?: continue
                val name = (b.getValue("name") as? Literal)?.stringValue() ?: iri.substringAfterLast('/')
                folderMap.getOrPut(iri) { FolderNode(iri, name) }
                (b.getValue("parent") as? IRI)?.stringValue()?.let { parentOf[iri] = it }
            }
        }
        // 2. Load ordered members (subfolders + elements/rels/views) via schema:itemListElement
        val memberQuery = """
            PREFIX arch: <${ns.core}>
            PREFIX schema: <https://schema.org/>
            SELECT ?folder ?item ?pos WHERE {
                ?folder a arch:Folder ;
                    schema:itemListElement ?li .
                ?li schema:item ?item ; schema:position ?pos .
            } ORDER BY ?folder ?pos
        """.trimIndent()
        conn.prepareTupleQuery(memberQuery).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val folderIri = (b.getValue("folder") as? IRI)?.stringValue() ?: continue
                val itemIri = (b.getValue("item") as? IRI)?.stringValue() ?: continue
                val folder = folderMap[folderIri] ?: continue
                val childFolder = folderMap[itemIri]
                if (childFolder != null) {
                    if (!folder.children.any { it.iri == itemIri }) folder.children.add(childFolder)
                } else {
                    subjects[itemIri]?.let { if (!folder.members.any { m -> m.iri == itemIri }) folder.members.add(it) }
                }
            }
        }
        // 2b. A folder placed in its parent by dcterms:isPartOf alone is a child of it as well. The converters'
        //     standard Elements, Relationships and Views folders are placed that way, and were otherwise neither
        //     a root nor anyone's child, so their members all ended up under "Unorganised". Added in name order,
        //     after the children the parent lists, and never where it would close a cycle.
        fun descends(from: FolderNode, target: FolderNode, seen: MutableSet<String> = mutableSetOf()): Boolean =
            from.iri == target.iri || (seen.add(from.iri) && from.children.any { descends(it, target, seen) })
        parentOf.entries.groupBy({ it.value }, { it.key }).forEach { (parentIri, childIris) ->
            val parent = folderMap[parentIri] ?: return@forEach
            childIris.mapNotNull { folderMap[it] }.sortedBy { it.name }.forEach { child ->
                if (parent.children.none { it.iri == child.iri } && !descends(child, parent)) parent.children.add(child)
            }
        }
        // 3. Return only root folders (no parent)
        return folderMap.values.filter { !parentOf.containsKey(it.iri) }.sortedBy { it.name }
    }

    /** The site's name: its model's, or its models' when there are a few, else "Architecture". */
    private fun siteTitle(conn: RepositoryConnection, subjects: Map<String, SubjectInfo>): String {
        val models = subjects.values.filter { it.kind == ResourceKind.MODEL }.map { it.label ?: getLabelCached(conn, it.iri) }.distinct().sorted()
        return when {
            models.size == 1 -> models[0]
            models.size in 2..3 -> models.joinToString(" · ")
            else -> "Architecture"
        }
    }

    private fun generateIndexPageWithConn(conn: RepositoryConnection, subjects: List<SubjectInfo>, site: Site, outputDir: File, graphConfig: GraphConfig = GraphConfig()) {
        val baseIri = site.baseIri
        val subjectMap = subjects.associateBy { it.iri }
        val roots = queryFolderTree(conn, baseIri, subjectMap)
        val back = ""

        // Fallback: subjects not in any folder, in a section per kind. A former name is not listed: its page is
        // there for an old link, not for a reader to browse to.
        val inFolder = mutableSetOf<String>()
        fun collectMembers(f: FolderNode) { f.members.forEach { inFolder.add(it.iri) }; f.children.forEach { collectMembers(it) } }
        roots.forEach { collectMembers(it) }
        val unfoldered = subjects.filter { it.iri !in inFolder && it.kind != ResourceKind.FORMER_NAME }

        fun slugOf(iri: String) = iri.hashCode().toUInt().toString(16)
        fun countIn(f: FolderNode): Int = f.members.size + f.children.sumOf { countIn(it) }

        // A row of a member table: its type's icon and its name, its type, its group.
        fun memberRow(item: SubjectInfo): String {
            val lbl = item.label ?: if (item.kind == ResourceKind.PROVENANCE) PagePaths.relative(item.iri, baseIri) else item.iri.substringAfterLast('/')
            val link = escapeHtml(PagePaths.urlDir(item.iri, baseIri) + "index.html")
            val look = if (item.kind in CONCEPT_KINDS) notation.lookOf(item.iri, item.kind == ResourceKind.RELATIONSHIP) else null
            val icon = look?.let { notation.looks.iconHtml(it, back) } ?: ""
            val isView = item.kind == ResourceKind.VIEW
            val thumb = if (isView && item.imageUrl != null) """<img src="${escapeHtml(item.imageUrl!!)}" class="thumb" alt="">""" else ""
            val type = look?.label ?: item.types.joinToString(", ").ifEmpty { KIND_NAMES[item.kind] ?: "" }
            val group = look?.group?.label ?: ""
            return """<tr data-row-type="${escapeHtml(look?.iri ?: "")}"><td data-sort="${escapeHtml(lbl)}"><span class="name-cell">$thumb$icon<a href="$link">${escapeHtml(lbl)}</a></span></td><td>${escapeHtml(type)}</td><td>${escapeHtml(group)}</td></tr>"""
        }
        fun memberTable(items: List<SubjectInfo>) = """<table class="sortable folder-table"><thead><tr><th>Name</th><th>Type</th><th>Group</th></tr></thead><tbody>
${items.joinToString("\n") { memberRow(it) }}
</tbody></table>"""

        // By folder: each folder a section with its members, its subfolders within it.
        fun renderFolderSection(f: FolderNode, depth: Int): String {
            val id = "sec-" + slugOf(f.iri)
            val body = (if (f.members.isNotEmpty()) memberTable(f.members) else "") + f.children.joinToString("\n") { renderFolderSection(it, depth + 1) }
            return """<section class="folder-section" id="$id">${group("📁 ${escapeHtml(f.name)}${count(countIn(f))}", body, open = depth <= 1)}</section>"""
        }
        val folderSections = roots.joinToString("\n") { renderFolderSection(it, 1) }
        fun unfolderedSection(id: String, heading: String, items: List<SubjectInfo>, open: Boolean = true): String {
            if (items.isEmpty()) return ""
            return """<section class="folder-section" id="$id">${group(heading + count(items.size), memberTable(items.sortedBy { (it.label ?: it.iri).lowercase() }), open)}</section>"""
        }
        val byKind = unfoldered.groupBy { it.kind }
        val unfolderedSections = listOf(
            unfolderedSection("sec-models", "Models", byKind[ResourceKind.MODEL].orEmpty()),
            unfolderedSection("sec-documentation", "Documentation", byKind[ResourceKind.DOCUMENTATION].orEmpty()),
            unfolderedSection("sec-unfoldered", "Unorganised", unfoldered.filter { it.kind in CONCEPT_KINDS }),
            unfolderedSection("sec-provenance", "Provenance", byKind[ResourceKind.PROVENANCE].orEmpty(), open = false),
        ).filter { it.isNotEmpty() }.joinToString("\n")

        // By type: the concepts in their language's groups (a layer, a domain), then by type.
        val concepts = subjects.filter { it.kind in CONCEPT_KINDS }
        fun byTypeSection(items: List<SubjectInfo>, relationship: Boolean): String {
            val looked = items.map { it to notation.lookOf(it.iri, relationship) }
            val groups = looked.groupBy { it.second?.group }
            return groups.entries.sortedWith(compareBy({ it.key?.order ?: Int.MAX_VALUE }, { it.key?.label ?: "~" })).joinToString("\n") { (g, inGroup) ->
                val types = inGroup.groupBy { it.second }.entries.sortedBy { it.key?.label ?: "~" }.joinToString("") { (look, members) ->
                    val title = (look?.let { notation.looks.iconHtml(it, back) + escapeHtml(it.label) } ?: "Untyped") + count(members.size)
                    group(title, memberTable(members.map { it.first }.sortedBy { (it.label ?: it.iri).lowercase() }), open = false)
                }
                group(escapeHtml(g?.label ?: "Other") + count(inGroup.size), types)
            }
        }
        val elements = concepts.filter { it.kind == ResourceKind.ELEMENT }
        val relationships = concepts.filter { it.kind == ResourceKind.RELATIONSHIP }
        val views = concepts.filter { it.kind == ResourceKind.VIEW }
        val byType = buildString {
            if (elements.isNotEmpty()) append("<h2>Elements ${count(elements.size)}</h2>").append(byTypeSection(elements, false))
            if (relationships.isNotEmpty()) append("<h2>Relationships ${count(relationships.size)}</h2>").append(byTypeSection(relationships, true))
            if (views.isNotEmpty()) append("<h2>Views ${count(views.size)}</h2>").append(memberTable(views.sortedBy { (it.label ?: it.iri).lowercase() }))
        }

        // The type chips: one per type of element, to show or hide its rows.
        val chips = elements.mapNotNull { notation.lookOf(it.iri, false) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy({ it.key.group?.order ?: Int.MAX_VALUE }, { it.key.label })).joinToString("") { (look, n) ->
                "<span class=\"chip\" data-type=\"${escapeHtml(look.iri)}\" title=\"Show or hide ${escapeHtml(look.label)}\">${look.iconHtml}${escapeHtml(look.label)} <small>$n</small></span>"
            }

        val title = siteTitle(conn, subjectMap)
        fun card(n: Int, label: String, href: String) = if (n == 0) "" else "<div class=\"card\"><a href=\"$href\"><div class=\"n\">$n</div><div class=\"l\">$label</div></a></div>"
        val cards = card(elements.size, "Elements", "#by-type") + card(relationships.size, "Relationships", "#by-type") + card(views.size, "Views", "#by-type") +
            card(byKind[ResourceKind.MODEL]?.size ?: subjects.count { it.kind == ResourceKind.MODEL }, "Models", "#sec-models") +
            card(subjects.count { it.kind == ResourceKind.DOCUMENTATION }, "Documentation", "#sec-documentation")


        fun toFolderInfo(f: FolderNode): GraphPageGenerator.FolderInfo =
            GraphPageGenerator.FolderInfo(f.iri, f.name, f.children.map { toFolderInfo(it) }, f.members.map { it.iri })
        // The graph is of the architecture: views, elements and relationships, not the records about them.
        val conceptMap = subjectMap.filterValues { it.kind in CONCEPT_KINDS }
        GraphPageGenerator.generate(conn, conceptMap, roots, baseIri, outputDir, "", roots.map { toFolderInfo(it) }, graphConfig, notation, title)

        File(outputDir, "index.html").writeText("""
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Architecture Index</title>
    <link rel="stylesheet" href="style.css">
</head>
${PageLayout.body(back)}
${PageLayout.header(back, "index", tree = true)}
<div class="shell">
${PageLayout.TREE_PANEL}
<main class="page">
    <h1>${escapeHtml(title)}</h1>
    <p class="faint">Architecture Index — every element, relationship and view, by folder or by type.</p>
    <div class="cards">$cards</div>
    <div class="toolbar">
        <input id="search" type="search" placeholder="Search by name or type…" data-filter="#index-content">
        <span class="seg" data-switch="index-view"><button class="on" data-show="#by-folder">By folder</button><button data-show="#by-type">By type</button></span>
        <button class="btn" data-expand="#index-content">Expand all</button><button class="btn" data-collapse="#index-content">Collapse all</button>
        <a href="#" id="show-all" class="btn hidden" onclick="showAll(); return false;">Show all folders</a>
    </div>
    ${if (chips.isNotEmpty()) "<div class=\"chips\" data-scope=\"#index-content\">$chips</div>" else ""}
    <div id="index-content">
    <div id="by-folder">
$folderSections
$unfolderedSections
    </div>
    <div id="by-type" class="hidden">
$byType
    </div>
    </div>
</main>
</div>
${PageLayout.scripts(back)}
</body></html>""".trimIndent())
    }

    private fun escapeHtml(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
