package archi.linked.rdf2docs

import archi.linked.rdf2docs.HtmlPageGenerator.ResourceKind
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.repository.RepositoryConnection
import java.io.File

/**
 * The model tree's data, `_assets/data/tree.js`: what the left panel of every page builds the tree from, as la-gram's
 * Model Explorer builds it from a model's contents — for each model, its views, its elements, its relationships, the
 * concepts of types no loaded language defines (*Unclassified*), and its folders with what each holds; and each
 * type's name and icon. `tree.js` lays them out.
 *
 * A concept is in the model it states with `arch:inModel`; else in the model whose namespace
 * (`vann:preferredNamespaceUri`) its IRI is in; else, with one model, in it; else in *Other*. A folder likewise, by its
 * IRI. A folder holds what it lists (`schema:itemListElement` / `schema:item`) and what states it is part of it
 * (`dcterms:isPartOf`); it is in the folder it is part of.
 */
internal object TreeDataGenerator {

    private const val SCHEMA = "https://schema.org/"
    private const val DCTERMS = "http://purl.org/dc/terms/"
    private const val VANN = "http://purl.org/vocab/vann/"

    private fun j(s: String?) = if (s == null) "null" else SiteAssets.js(s)

    fun generate(conn: RepositoryConnection, notation: NotationSupport, ns: Namespaces, site: Site, subjects: Map<String, HtmlPageGenerator.SubjectInfo>, outputDir: File) {
        val models = subjects.values.filter { it.kind == ResourceKind.MODEL }.sortedBy { (it.label ?: it.iri).lowercase() }
        val namespaces = models.mapNotNull { m ->
            conn.getStatements(conn.valueFactory.createIRI(m.iri), conn.valueFactory.createIRI(VANN, "preferredNamespaceUri"), null, true)
                .use { res -> res.firstOrNull()?.`object`?.stringValue() }?.let { it to m.iri }
        }.sortedByDescending { it.first.length }
        val inModel = HashMap<String, String>()
        conn.prepareTupleQuery("SELECT ?s ?m WHERE { ?s <${ns.core}inModel> ?m }").evaluate().use { res ->
            res.forEach { b -> val s = b.getValue("s")?.stringValue(); val m = b.getValue("m")?.stringValue(); if (s != null && m != null && subjects[m]?.kind == ResourceKind.MODEL) inModel.putIfAbsent(s, m) }
        }
        val only = models.singleOrNull()?.iri
        fun modelOf(iri: String): String = inModel[iri] ?: namespaces.firstOrNull { iri.startsWith(it.first) }?.second ?: only ?: ""

        // The folders: their names, parents and members, from either end of a membership.
        data class Folder(val iri: String, var label: String, var parent: String? = null, val members: LinkedHashSet<String> = linkedSetOf())
        val folders = linkedMapOf<String, Folder>()
        conn.prepareTupleQuery("""
            PREFIX arch: <${ns.core}> PREFIX schema: <$SCHEMA> PREFIX dcterms: <$DCTERMS>
            SELECT ?f ?name ?parent WHERE { ?f a arch:Folder . OPTIONAL { ?f schema:name ?name } OPTIONAL { ?f dcterms:isPartOf ?parent . ?parent a arch:Folder } }
        """.trimIndent()).evaluate().use { res ->
            res.forEach { b ->
                val f = (b.getValue("f") as? IRI)?.stringValue() ?: return@forEach
                val folder = folders.getOrPut(f) { Folder(f, f.trimEnd('/').substringAfterLast('/')) }
                (b.getValue("name") as? Literal)?.stringValue()?.let { folder.label = it }
                (b.getValue("parent") as? IRI)?.stringValue()?.let { folder.parent = it }
            }
        }
        conn.prepareTupleQuery("""
            PREFIX arch: <${ns.core}> PREFIX schema: <$SCHEMA> PREFIX dcterms: <$DCTERMS>
            SELECT ?f ?m ?pos WHERE {
              { ?f a arch:Folder ; schema:itemListElement ?li . ?li schema:item ?m . OPTIONAL { ?li schema:position ?pos } }
              UNION { ?m dcterms:isPartOf ?f . ?f a arch:Folder . FILTER NOT EXISTS { ?m a arch:Folder } }
            } ORDER BY ?f ?pos
        """.trimIndent()).evaluate().use { res ->
            res.forEach { b ->
                val f = (b.getValue("f") as? IRI)?.stringValue() ?: return@forEach
                val m = (b.getValue("m") as? IRI)?.stringValue() ?: return@forEach
                // A folder a folder lists is in it, as much as one that says it is part of it.
                val sub = folders[m]
                if (sub != null) { if (sub.parent == null && m != f) sub.parent = f }
                else folders[f]?.members?.add(m)
            }
        }

        // The concepts, each in its model's group.
        data class Concept(val iri: String, val label: String, val href: String, val type: String?)
        class Contents {
            val views = mutableListOf<Pair<Concept, String>>()
            val elements = mutableListOf<Concept>()
            val relationships = mutableListOf<Concept>()
            val unclassified = mutableListOf<Concept>()
            val folders = mutableListOf<Folder>()
        }
        val contents = linkedMapOf<String, Contents>()
        models.forEach { contents[it.iri] = Contents() }
        val typeIcons = linkedMapOf<String, Pair<String, String>>()
        fun typed(iri: String, relationship: Boolean): String? {
            val type = notation.typeOf(iri) ?: return null
            val key = type + if (relationship) "|r" else ""
            if (key !in typeIcons) {
                val look = notation.looks.look(type, relationship)
                typeIcons[key] = look.label to look.iconHtml
            }
            return key
        }
        for (info in subjects.values) {
            if (info.kind !in HtmlPageGenerator.CONCEPT_KINDS) continue
            val c = contents.getOrPut(modelOf(info.iri)) { Contents() }
            val href = PagePaths.urlDir(info.iri, site.baseIri) + "index.html"
            val label = info.label ?: info.iri.trimEnd('/').substringAfterLast('/')
            when (info.kind) {
                ResourceKind.VIEW -> {
                    val kind = when { "Catalog" in info.types -> "catalog"; "Matrix" in info.types -> "matrix"; else -> "diagram" }
                    c.views.add(Concept(info.iri, label, href, typed(info.iri, false)) to kind)
                }
                ResourceKind.RELATIONSHIP -> c.relationships.add(Concept(info.iri, label, href, typed(info.iri, true)))
                else -> {
                    val type = notation.typeOf(info.iri)
                    val concept = Concept(info.iri, label, href, typed(info.iri, false))
                    // A type no loaded language defines: unclassified, as la-gram's tree has it.
                    if (type == null || !notation.pack.knows(type)) c.unclassified.add(concept) else c.elements.add(concept)
                }
            }
        }
        for (f in folders.values) contents.getOrPut(modelOf(f.iri)) { Contents() }.folders.add(f)

        val json = StringBuilder(64 * 1024)
        json.append("{\"types\":{")
        typeIcons.entries.forEachIndexed { i, (k, v) -> if (i > 0) json.append(','); json.append(j(k)).append(":[").append(j(v.first)).append(',').append(j(v.second)).append(']') }
        json.append("},\"models\":[")
        contents.entries.filter { (_, c) -> c.views.size + c.elements.size + c.relationships.size + c.unclassified.size > 0 || c.folders.isNotEmpty() }
            .forEachIndexed { i, (modelIri, c) ->
                if (i > 0) json.append(',')
                val model = subjects[modelIri]
                json.append("{\"iri\":").append(j(modelIri.ifEmpty { null }))
                    .append(",\"label\":").append(j(model?.label ?: if (modelIri.isEmpty()) "Other" else modelIri.trimEnd('/').substringAfterLast('/')))
                    .append(",\"href\":").append(j(model?.let { PagePaths.urlDir(it.iri, site.baseIri) + "index.html" }))
                fun concepts(name: String, list: List<Concept>) {
                    json.append(",\"").append(name).append("\":[")
                    list.forEachIndexed { k, x -> if (k > 0) json.append(','); json.append('[').append(j(x.iri)).append(',').append(j(x.label)).append(',').append(j(x.href)).append(',').append(j(x.type)).append(']') }
                    json.append(']')
                }
                json.append(",\"views\":[")
                c.views.forEachIndexed { k, (x, kind) -> if (k > 0) json.append(','); json.append('[').append(j(x.iri)).append(',').append(j(x.label)).append(',').append(j(x.href)).append(',').append(j(x.type)).append(',').append(j(kind)).append(']') }
                json.append(']')
                concepts("elements", c.elements)
                concepts("relationships", c.relationships)
                concepts("unclassified", c.unclassified)
                json.append(",\"folders\":[")
                c.folders.forEachIndexed { k, f ->
                    if (k > 0) json.append(',')
                    json.append('[').append(j(f.iri)).append(',').append(j(f.label)).append(',').append(j(f.parent)).append(",[")
                        .append(f.members.joinToString(",") { j(it) }).append("],").append(j("sec-" + f.iri.hashCode().toUInt().toString(16))).append(']')
                }
                json.append("]}")
            }
        json.append("]}")
        SiteAssets.writeData(outputDir, "tree", "LA_TREE", json.toString())
    }
}
