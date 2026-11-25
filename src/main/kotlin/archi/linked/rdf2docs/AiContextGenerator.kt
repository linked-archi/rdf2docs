package archi.linked.rdf2docs

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Writes the graph as Markdown for an AI assistant to read.
 *
 * The document is **partitioned per model** rather than emitted as one file. A merged
 * multi-notation graph produces megabytes of Markdown — far past any model's context window — so a
 * single document cannot be used for the purpose its own header describes. One file per model is a
 * few hundred KB and can be loaded on demand; [generate] writes an index alongside them so the
 * "one document to hand an agent" property survives.
 *
 * Within a partition the order is the model, then views, elements and relationships, then the model's
 * documentation. Relationships come after elements because they are the least useful thing to read first
 * and by far the most numerous, and because a relationship is only meaningful once its endpoints have
 * been introduced. Documentation sections close the partition: they are prose, often long, and quoted
 * whole.
 */
object AiContextGenerator {

    private val log = LoggerFactory.getLogger(AiContextGenerator::class.java)

    /** One partition document, and what it covers. */
    data class Partition(
        val key: String,
        val file: File,
        val views: Int,
        val elements: Int,
        val relationships: Int,
    ) {
        val resources: Int get() = views + elements + relationships
    }

    data class Result(
        val elements: Int,
        val views: Int,
        val relationships: Int,
        val partitions: List<Partition>,
    )

    /**
     * What the document covers: the architecture, its models and their documentation. Not the provenance
     * records, which say how the graph was made rather than what it describes, nor former names.
     */
    private val KINDS = HtmlPageGenerator.CONCEPT_KINDS + setOf(
        HtmlPageGenerator.ResourceKind.MODEL,
        HtmlPageGenerator.ResourceKind.DOCUMENTATION,
    )

    fun generate(
        repository: Repository,
        baseIri: String,
        outputFile: File,
        htmlBaseUrl: String? = null,
        ns: Namespaces,
    ): Result {
        repository.connection.use { conn ->
            val subjects = HtmlPageGenerator.querySubjects(conn, baseIri, ns).filterValues { it.kind in KINDS }
            val models = Models.read(conn, baseIri, ns)
            val byPartition = subjects.values
                .groupBy { models.partitionKey(it) }
                .toSortedMap()

            val partitions = if (byPartition.size <= 1) {
                // A single model needs no index and no subdirectory: the document stays exactly
                // where every existing link and script expects it.
                val all = byPartition.values.firstOrNull().orEmpty()
                outputFile.parentFile?.mkdirs()
                outputFile.writeText(renderPartition(conn, null, all, baseIri, htmlBaseUrl, ns))
                listOf(partitionOf(byPartition.keys.firstOrNull() ?: "", outputFile, all))
            } else {
                val dir = File(outputFile.parentFile, outputFile.nameWithoutExtension)
                dir.mkdirs()
                val written = byPartition.map { (key, infos) ->
                    val file = File(dir, "${slug(key)}.md")
                    file.writeText(renderPartition(conn, key, infos, baseIri, htmlBaseUrl, ns))
                    partitionOf(key, file, infos)
                }
                outputFile.writeText(renderIndex(written, outputFile))
                log.info("AI context partitioned into {} documents under {}", written.size, dir.absolutePath)
                written
            }

            return Result(
                elements = partitions.sumOf { it.elements },
                views = partitions.sumOf { it.views },
                relationships = partitions.sumOf { it.relationships },
                partitions = partitions,
            )
        }
    }

    /**
     * Which document a resource belongs in: its model's, read from the triples.
     *
     * A resource's model is the one it states with `arch:inModel`. One that states none — a LeanIX
     * stakeholder, a Structurizr documentation section — is in the model whose namespace its IRI is in, the
     * `vann:preferredNamespaceUri` the model states. A model is its own. The key is that namespace below the
     * base, `model/payment-platform`, so that it reads as the model it names.
     *
     * Not the IRI's path: a Backstage id has three segments of its own, and DD-41 asks consumers to read a
     * resource's model from the triples. When `--model-id` has already folded the model into `--base`, every
     * key is empty and the graph is written as one document, which is the right answer for a single-model run.
     */
    private class Models(
        private val baseIri: String,
        /** Each model's namespace, longest first, so the closest one matches. */
        private val namespaces: List<Pair<String, String>>,
        private val inModel: Map<String, String>,
    ) {
        private val namespaceOf: Map<String, String> = namespaces.associate { (model, ns) -> model to ns }

        fun partitionKey(info: HtmlPageGenerator.SubjectInfo): String {
            if (info.kind == HtmlPageGenerator.ResourceKind.MODEL) return key(info.iri)
            inModel[info.iri]?.let { return key(it) }
            val model = namespaces.firstOrNull { (_, ns) -> info.iri.startsWith(ns) }?.first ?: return ""
            return key(model)
        }

        private fun key(model: String): String = PagePaths.relative(namespaceOf[model] ?: stem(model), baseIri)

        companion object {
            fun read(conn: RepositoryConnection, baseIri: String, ns: Namespaces): Models {
                val namespaces = mutableMapOf<String, String>()
                val modelQuery = """
                    PREFIX arch: <${ns.core}>
                    PREFIX vann: <http://purl.org/vocab/vann/>
                    SELECT ?model ?ns WHERE {
                        ?model a arch:Model .
                        OPTIONAL { ?model vann:preferredNamespaceUri ?ns }
                    }
                """.trimIndent()
                conn.prepareTupleQuery(modelQuery).evaluate().use { res ->
                    while (res.hasNext()) {
                        val b = res.next()
                        val model = (b.getValue("model") as? IRI)?.stringValue() ?: continue
                        val stated = b.getValue("ns")?.stringValue()
                        if (stated != null || model !in namespaces) namespaces[model] = withSlash(stated ?: stem(model))
                    }
                }
                val inModel = mutableMapOf<String, String>()
                val memberQuery = """
                    PREFIX arch: <${ns.core}>
                    SELECT ?member ?model WHERE { ?member arch:inModel ?model } ORDER BY ?member ?model
                """.trimIndent()
                conn.prepareTupleQuery(memberQuery).evaluate().use { res ->
                    while (res.hasNext()) {
                        val b = res.next()
                        val member = (b.getValue("member") as? IRI)?.stringValue() ?: continue
                        val model = (b.getValue("model") as? IRI)?.stringValue() ?: continue
                        inModel.putIfAbsent(member, model)
                    }
                }
                val ordered = namespaces.entries.map { it.key to it.value }.sortedByDescending { it.second.length }
                return Models(baseIri, ordered, inModel)
            }

            /**
             * A model's namespace when it states none: `{ns}model` less its last segment, or a model IRI from
             * before DD-41, `{base}{notation}/{modelId}`, as it is.
             */
            private fun stem(model: String): String = if (model.endsWith("/model")) model.removeSuffix("model") else model

            private fun withSlash(ns: String) = if (ns.endsWith("/") || ns.endsWith("#")) ns else "$ns/"
        }
    }

    private fun partitionOf(key: String, file: File, infos: List<HtmlPageGenerator.SubjectInfo>) = Partition(
        key = key,
        file = file,
        views = infos.count { it.kind == HtmlPageGenerator.ResourceKind.VIEW },
        elements = infos.count { it.kind == HtmlPageGenerator.ResourceKind.ELEMENT },
        relationships = infos.count { it.kind == HtmlPageGenerator.ResourceKind.RELATIONSHIP },
    )

    private fun slug(key: String): String =
        key.ifEmpty { "root" }.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "root" }

    // ---- Rendering ----

    private fun appendInstructions(sb: StringBuilder) {
        sb.appendLine("## Instructions for AI Assistant")
        sb.appendLine()
        sb.appendLine("When answering questions about this architecture:")
        sb.appendLine("- Always cite specific IRIs as references")
        sb.appendLine("- Use the format: [Element Name](IRI)")
        sb.appendLine("- Provide concrete examples from the graph")
        sb.appendLine("- Explain relationships between elements")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
    }

    private fun renderIndex(partitions: List<Partition>, outputFile: File): String {
        val sb = StringBuilder()
        sb.appendLine("# Architecture Knowledge Base")
        sb.appendLine()
        sb.appendLine("This graph is documented in ${partitions.size} partitions, one per model, because a single")
        sb.appendLine("document would not fit in a context window. Load the partition that covers the model you")
        sb.appendLine("are asked about; each one is self-contained and cites full IRIs.")
        sb.appendLine()
        appendInstructions(sb)
        sb.appendLine("## Partitions")
        sb.appendLine()
        sb.appendLine("| Model | Document | Views | Elements | Relationships |")
        sb.appendLine("|---|---|---|---|---|")
        val dirName = outputFile.nameWithoutExtension
        partitions.forEach { p ->
            val link = "$dirName/${p.file.name}"
            sb.appendLine("| `${p.key.ifEmpty { "(base)" }}` | [${p.file.name}]($link) | ${p.views} | ${p.elements} | ${p.relationships} |")
        }
        sb.appendLine(
            "| **Total** | ${partitions.size} documents | **${partitions.sumOf { it.views }}** " +
                "| **${partitions.sumOf { it.elements }}** | **${partitions.sumOf { it.relationships }}** |"
        )
        sb.appendLine()
        return sb.toString()
    }

    private fun renderPartition(
        conn: RepositoryConnection,
        key: String?,
        infos: List<HtmlPageGenerator.SubjectInfo>,
        baseIri: String,
        htmlBaseUrl: String?,
        ns: Namespaces,
    ): String {
        val sb = StringBuilder()
        sb.appendLine(if (key.isNullOrEmpty()) "# Architecture Knowledge Base" else "# Architecture Knowledge Base — $key")
        sb.appendLine()
        sb.appendLine("This document contains architectural information extracted from the RDF knowledge graph.")
        sb.appendLine("All references include direct links to the source IRIs in the graph.")
        if (!key.isNullOrEmpty()) {
            sb.appendLine()
            sb.appendLine("Covers the model `$key`. Other models are listed in the sibling `ai-context.md` index.")
        }
        sb.appendLine()
        appendInstructions(sb)

        val modelsHere = infos.filter { it.kind == HtmlPageGenerator.ResourceKind.MODEL }.sortedBy { displayKey(it) }
        val views = infos.filter { it.kind == HtmlPageGenerator.ResourceKind.VIEW }.sortedBy { displayKey(it) }
        val elements = infos.filter { it.kind == HtmlPageGenerator.ResourceKind.ELEMENT }
        val relationships = infos.filter { it.kind == HtmlPageGenerator.ResourceKind.RELATIONSHIP }
        val documentation = infos.filter { it.kind == HtmlPageGenerator.ResourceKind.DOCUMENTATION }.sortedBy { displayKey(it) }

        if (modelsHere.isNotEmpty()) {
            sb.appendLine(if (modelsHere.size == 1) "## Model" else "## Models")
            sb.appendLine()
            modelsHere.forEach { info ->
                sb.appendLine("### [${displayName(conn, info)}](${info.iri})")
                sb.appendLine()
                appendResourceBody(sb, conn, info, baseIri, htmlBaseUrl, ns)
            }
        }

        if (views.isNotEmpty()) {
            sb.appendLine("## Views")
            sb.appendLine()
            views.forEach { info ->
                sb.appendLine("### [${displayName(conn, info)}](${info.iri})")
                sb.appendLine()
                appendResourceBody(sb, conn, info, baseIri, htmlBaseUrl, ns)
            }
        }

        appendTypeGroups(sb, "Elements", elements, conn, baseIri, htmlBaseUrl, ns)
        appendTypeGroups(sb, "Relationships", relationships, conn, baseIri, htmlBaseUrl, ns)

        if (documentation.isNotEmpty()) {
            sb.appendLine("## Documentation")
            sb.appendLine()
            documentation.forEach { appendDocumentation(sb, conn, it, baseIri, htmlBaseUrl) }
        }

        return sb.toString()
    }

    /**
     * A documentation section: what it documents, and its text as a quotation, line by line. Quoted rather
     * than inlined, so that a heading in the section's own Markdown does not become a heading of this
     * document.
     */
    private fun appendDocumentation(
        sb: StringBuilder,
        conn: RepositoryConnection,
        info: HtmlPageGenerator.SubjectInfo,
        baseIri: String,
        htmlBaseUrl: String?,
    ) {
        sb.appendLine("### [${info.label ?: info.iri.substringAfterLast('/')}](${info.iri})")
        sb.appendLine()
        sb.appendLine("- **IRI**: `${info.iri}`")
        val ownersQuery = """
            PREFIX schema: <https://schema.org/>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            SELECT ?owner (SAMPLE(?ownerLabel) AS ?label) WHERE {
                ?owner schema:subjectOf <${info.iri}> .
                OPTIONAL { ?owner skos:prefLabel ?ownerLabel }
            } GROUP BY ?owner ORDER BY ?owner
        """.trimIndent()
        conn.prepareTupleQuery(ownersQuery).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val owner = (b.getValue("owner") as? IRI)?.stringValue() ?: continue
                val label = (b.getValue("label") as? Literal)?.stringValue() ?: owner.substringAfterLast('/')
                sb.appendLine("- **Documents**: [$label]($owner)")
            }
        }
        var text: String? = null
        var format: String? = null
        val textQuery = """
            PREFIX schema: <https://schema.org/>
            SELECT ?text ?format WHERE {
                OPTIONAL { <${info.iri}> schema:text ?text }
                OPTIONAL { <${info.iri}> schema:encodingFormat ?format }
            } LIMIT 1
        """.trimIndent()
        conn.prepareTupleQuery(textQuery).evaluate().use { res ->
            if (res.hasNext()) {
                val b = res.next()
                text = (b.getValue("text") as? Literal)?.stringValue()
                format = (b.getValue("format") as? Literal)?.stringValue()
            }
        }
        format?.let { sb.appendLine("- **Format**: $it") }
        if (htmlBaseUrl != null) {
            sb.appendLine("- **Data Model**: ${htmlBaseUrl.trimEnd('/')}/${PagePaths.urlDir(info.iri, baseIri)}index.html")
        }
        sb.appendLine()
        text?.let { body ->
            body.trimEnd().lines().forEach { line -> sb.appendLine(if (line.isBlank()) ">" else "> $line") }
            sb.appendLine()
        }
    }

    private fun appendTypeGroups(
        sb: StringBuilder,
        section: String,
        infos: List<HtmlPageGenerator.SubjectInfo>,
        conn: RepositoryConnection,
        baseIri: String,
        htmlBaseUrl: String?,
        ns: Namespaces,
    ) {
        if (infos.isEmpty()) return
        sb.appendLine("## $section")
        sb.appendLine()
        infos.groupBy { it.types.firstOrNull() ?: "Unknown" }
            .entries.sortedBy { it.key }
            .forEach { (type, group) ->
                sb.appendLine("### $type")
                sb.appendLine()
                group.sortedBy { displayKey(it) }.forEach { info ->
                    sb.appendLine("#### [${displayName(conn, info)}](${info.iri})")
                    sb.appendLine()
                    appendResourceBody(sb, conn, info, baseIri, htmlBaseUrl, ns)
                }
            }
    }

    private fun appendResourceBody(
        sb: StringBuilder,
        conn: RepositoryConnection,
        info: HtmlPageGenerator.SubjectInfo,
        baseIri: String,
        htmlBaseUrl: String?,
        ns: Namespaces,
    ) {
        sb.appendLine("- **IRI**: `${info.iri}`")
        if (info.types.isNotEmpty()) sb.appendLine("- **Type**: ${info.types.joinToString(", ")}")
        if (info.imageUrl != null) sb.appendLine("- **Diagram**: ${info.imageUrl}")
        if (htmlBaseUrl != null) {
            sb.appendLine("- **Data Model**: ${htmlBaseUrl.trimEnd('/')}/${PagePaths.urlDir(info.iri, baseIri)}index.html")
        }
        val props = getCustomProperties(conn, info.iri)
        if (props.isNotEmpty()) {
            sb.appendLine("- **Metadata**:")
            props.forEach { (k, v) -> sb.appendLine("  - $k: $v") }
        }

        val isView = info.kind == HtmlPageGenerator.ResourceKind.VIEW
        if (isView) {
            val contents = getViewContents(conn, info.iri, ns)
            if (contents.isNotEmpty()) {
                sb.appendLine("- **Contains**:")
                contents.forEach { (iri, lbl) -> sb.appendLine("  - [$lbl]($iri)") }
            }
        }

        val relationships = getRelationships(conn, info.iri)
        if (isView) {
            val links = relationships.filter { it.predicate !in VIEW_STRUCTURAL_PREDICATES }
            if (links.isNotEmpty()) {
                sb.appendLine("- **Links**:")
                links.forEach { rel -> sb.appendLine("  - ${rel.predicate}: ${rel.target}") }
            }
        } else if (relationships.isNotEmpty()) {
            sb.appendLine("- **Relationships**:")
            relationships.forEach { rel -> sb.appendLine("  - ${rel.predicate}: [${rel.targetLabel}](${rel.target})") }
        }
        sb.appendLine()
    }

    private val VIEW_STRUCTURAL_PREDICATES = setOf("view", "archElement", "archRelationship", "source", "target")

    /** Sort key: the label when there is one, the IRI otherwise, so ordering is stable either way. */
    private fun displayKey(info: HtmlPageGenerator.SubjectInfo) = info.label ?: info.iri

    /**
     * The heading for a resource.
     *
     * A relationship rarely carries a `skos:prefLabel`, and its id is synthesised from its endpoints,
     * so the fallback used for everything else — the last IRI segment — produces headings no reader
     * can scan (`order-service__payment-gateway__submits`). Naming it by its endpoints instead costs
     * nothing: they are already resolved for the body of the entry.
     */
    private fun displayName(conn: RepositoryConnection, info: HtmlPageGenerator.SubjectInfo): String {
        info.label?.let { return it }
        if (info.kind == HtmlPageGenerator.ResourceKind.RELATIONSHIP) {
            val endpoints = getRelationships(conn, info.iri)
            val source = endpoints.firstOrNull { it.predicate == "source" }?.targetLabel
            val target = endpoints.firstOrNull { it.predicate == "target" }?.targetLabel
            if (source != null && target != null) return "$source → $target"
        }
        return info.iri.substringAfterLast('/')
    }

    private fun getViewContents(conn: RepositoryConnection, viewIri: String, ns: Namespaces): List<Pair<String, String>> {
        val query = """
            PREFIX archvis: <${ns.coreVis}>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

            SELECT DISTINCT ?elem ?elemLabel
            WHERE {
                ?node archvis:view <$viewIri> .
                ?node archvis:archElement ?elem .
                OPTIONAL { ?elem skos:prefLabel ?elemLabel }
            }
            ORDER BY ?elemLabel
        """.trimIndent()
        val contents = mutableListOf<Pair<String, String>>()
        conn.prepareTupleQuery(query).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val elemIri = (b.getValue("elem") as? IRI)?.stringValue() ?: continue
                val elemLabel = (b.getValue("elemLabel") as? Literal)?.stringValue()
                    ?: elemIri.substringAfterLast('/')
                contents.add(elemIri to elemLabel)
            }
        }
        return contents
    }

    private fun getCustomProperties(conn: RepositoryConnection, subjectIri: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        // Direct literal properties (e.g. vocab:archiUrl)
        val literalQuery = """
            SELECT ?predicate ?value WHERE {
                <$subjectIri> ?predicate ?value .
                FILTER(isLiteral(?value))
                FILTER(?predicate != <http://www.w3.org/2004/02/skos/core#prefLabel>)
                FILTER(?predicate != <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>)
            } ORDER BY ?predicate
        """.trimIndent()
        conn.prepareTupleQuery(literalQuery).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val pred = (b.getValue("predicate") as? IRI)?.localName ?: continue
                val value = (b.getValue("value") as? Literal)?.stringValue() ?: continue
                result.add(pred to value)
            }
        }
        // schema:additionalProperty blanknode key/value pairs
        val bnQuery = """
            PREFIX schema: <https://schema.org/>
            SELECT ?key ?value WHERE {
                <$subjectIri> schema:additionalProperty ?bn .
                ?bn schema:name ?key ; schema:value ?value .
            } ORDER BY ?key
        """.trimIndent()
        conn.prepareTupleQuery(bnQuery).evaluate().use { res ->
            while (res.hasNext()) {
                val b = res.next()
                val key = (b.getValue("key") as? Literal)?.stringValue() ?: continue
                val value = (b.getValue("value") as? Literal)?.stringValue() ?: continue
                result.add(key to value)
            }
        }
        return result
    }

    private fun getRelationships(conn: RepositoryConnection, subjectIri: String): List<RelationshipInfo> {
        val query = """
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            
            SELECT ?predicate ?object ?objectLabel
            WHERE {
                <$subjectIri> ?predicate ?object .
                FILTER(isIRI(?object))
                FILTER(?predicate != <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>)
                FILTER(?predicate != <http://purl.org/dc/terms/isPartOf>)
                OPTIONAL { ?object skos:prefLabel ?objectLabel }
            }
        """.trimIndent()

        val relationships = mutableListOf<RelationshipInfo>()
        conn.prepareTupleQuery(query).evaluate().use { res ->
            while (res.hasNext()) {
                val binding = res.next()
                val predicate = binding.getValue("predicate") as? IRI ?: continue
                val obj = binding.getValue("object") as? IRI ?: continue
                val objLabel = (binding.getValue("objectLabel") as? Literal)?.stringValue()
                    ?: obj.localName ?: obj.stringValue()

                val predicateLabel = predicate.localName ?: predicate.stringValue()
                relationships.add(RelationshipInfo(predicateLabel, obj.stringValue(), objLabel))
            }
        }

        return relationships
    }

    private data class RelationshipInfo(
        val predicate: String,
        val target: String,
        val targetLabel: String
    )
}
