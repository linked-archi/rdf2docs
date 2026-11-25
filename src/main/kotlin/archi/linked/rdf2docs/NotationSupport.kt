package archi.linked.rdf2docs

import archi.linked.rdf2docs.notation.DiagramRenderer
import archi.linked.rdf2docs.notation.ModelLabels
import archi.linked.rdf2docs.notation.NS
import archi.linked.rdf2docs.notation.NotationPack
import archi.linked.rdf2docs.notation.PresentationContext
import archi.linked.rdf2docs.notation.Triples
import archi.linked.rdf2docs.notation.TypeChooser
import archi.linked.rdf2docs.notation.localName
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.slf4j.LoggerFactory
import java.io.File

/**
 * What a run draws with: the model as one graph to read (every named graph's statements together), the notation
 * pack of the languages it uses, the presentation context the site is drawn for by default, and the renderer, icons
 * and type looks built on them. Made once per run and shared by every page.
 */
class NotationSupport(conn: RepositoryConnection, outputDir: File, presentationContext: String?) {
    private val log = LoggerFactory.getLogger(NotationSupport::class.java)

    val model = LinkedHashModel().also { m -> conn.getStatements(null, null, null, true).use { res -> res.forEach { m.add(it) } } }
    val t = Triples(model)

    /** The model's prefixes, and the ones the published vocabularies are known by. */
    val prefixes: Map<String, String> = linkedMapOf(
        "rdf" to NS.RDF, "rdfs" to NS.RDFS, "xsd" to NS.XSD, "skos" to NS.SKOS, "skosxl" to NS.SKOSXL, "schema" to NS.SCHEMA,
        "dcterms" to NS.DCTERMS, "arch" to NS.ARCH, "arch-vis" to NS.VIS, "prov" to "http://www.w3.org/ns/prov#",
        "owl" to "http://www.w3.org/2002/07/owl#", "vann" to "http://purl.org/vocab/vann/",
    ).also { map -> conn.namespaces.use { res -> res.forEach { ns -> if (ns.prefix.isNotEmpty()) map.putIfAbsent(ns.prefix, ns.name) } } }

    val pack: NotationPack = run {
        val declared = model.filter(null, archi.linked.rdf2docs.notation.iri(NS.ARCH + "modelConformsToMetamodel"), null)
            .mapNotNull { (it.`object` as? IRI)?.stringValue() }.distinct()
        val iris = model.filter(null, archi.linked.rdf2docs.notation.iri(NS.TYPE), null).mapNotNull { (it.`object` as? IRI)?.stringValue() }.toSet() + declared
        NotationPack.load(iris, declared)
    }

    /** The presentation contexts the model's languages offer. */
    val contexts: List<PresentationContext> = pack.contexts

    /**
     * The context the site is drawn for by default: given by IRI, by local name or by name, case aside; none when
     * none is given or none matches — each language's default notation then.
     */
    val defaultContext: String? = presentationContext?.trim()?.takeIf { it.isNotEmpty() }?.let { wanted ->
        val found = contexts.firstOrNull { it.iri == wanted }
            ?: contexts.firstOrNull { localName(it.iri).equals(wanted, true) || it.label.equals(wanted, true) }
            ?: contexts.firstOrNull { localName(it.iri).removeSuffix("Context").equals(wanted.removeSuffix("Context"), true) }
        if (found == null) log.warn("Presentation context '{}' is none of {}: drawing with the default notation", wanted, contexts.map { localName(it.iri) })
        found?.iri
    }

    val styles = pack.styles(defaultContext)
    val labels = ModelLabels(t, { pack.typeLabel(it) }, prefixes)
    val chooser = TypeChooser(t, pack)
    val icons = SiteIcons()
    val looks = TypeLooks(pack, styles)
    val renderer = DiagramRenderer(t, pack, icons, labels, chooser)

    /** The type a concept is drawn and listed as. */
    fun typeOf(iri: String): String? = chooser.typeOf(iri, styles)

    /** How a concept's type looks, or null for an untyped one. */
    fun lookOf(iri: String, relationship: Boolean): TypeLook? = typeOf(iri)?.let { looks.look(it, relationship) }

    /** The name of the default drawing in the context switch: the active notation set's name. */
    val defaultLabel: String get() = pack.styles(null).activeSet?.let { pack.t.labelOf(it) } ?: "Default notation"
}
