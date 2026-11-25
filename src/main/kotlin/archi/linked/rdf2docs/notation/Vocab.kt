package archi.linked.rdf2docs.notation

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import kotlin.math.abs
import kotlin.math.roundToLong

/** The vocabularies the notation layer reads, by namespace. */
object NS {
    const val META = "https://meta.linked.archi/"
    const val ARCH = "https://meta.linked.archi/core#"
    const val VIS = "https://meta.linked.archi/core-vis#"
    const val CORE_NOTATION = "https://meta.linked.archi/core-notation#"
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val RDFS = "http://www.w3.org/2000/01/rdf-schema#"
    const val SKOS = "http://www.w3.org/2004/02/skos/core#"
    const val SKOSXL = "http://www.w3.org/2008/05/skos-xl#"
    const val SCHEMA = "https://schema.org/"
    const val DCTERMS = "http://purl.org/dc/terms/"
    const val XSD = "http://www.w3.org/2001/XMLSchema#"
    const val TYPE = RDF + "type"
}

private val vf = SimpleValueFactory.getInstance()

/** An IRI value. */
internal fun iri(value: String): IRI = vf.createIRI(value)

/**
 * Statements read by subject and predicate, whatever graph states them: the notation pack and the model are both
 * read through this, so that the code ported from la-gram reads like the original, which reads an n3 store.
 */
class Triples(val model: Model) {
    fun objects(subject: Resource, predicate: String): List<Value> =
        model.filter(subject, iri(predicate), null).map { it.`object` }.distinct()

    fun objects(subject: String, predicate: String): List<Value> = objects(iri(subject), predicate)

    fun subjects(predicate: String, obj: Value): List<Resource> =
        model.filter(null, iri(predicate), obj).map { it.subject }.distinct()

    fun subjectsOfType(type: String): List<Resource> = subjects(NS.TYPE, iri(type))

    fun first(subject: Resource, predicate: String): Value? = model.filter(subject, iri(predicate), null).firstOrNull()?.`object`

    fun firstString(subject: Resource, predicate: String): String? = first(subject, predicate)?.stringValue()

    fun types(subject: Resource): List<String> = objects(subject, NS.TYPE).filterIsInstance<IRI>().map { it.stringValue() }

    fun has(subject: Resource, predicate: String, obj: Value): Boolean = model.contains(subject, iri(predicate), obj)

    /**
     * A label: English `skos:prefLabel`, any `skos:prefLabel`, `rdfs:label`, else the IRI fragment — as the kernel's
     * `labelFor` reads one.
     */
    fun labelOf(subject: String): String {
        val s = iri(subject)
        for (predicate in listOf(NS.SKOS + "prefLabel", NS.RDFS + "label")) {
            val literals = objects(s, predicate).filterIsInstance<Literal>()
            val chosen = literals.firstOrNull { it.language.orElse(null) == "en" } ?: literals.firstOrNull()
            if (chosen != null) return chosen.stringValue()
        }
        return localName(subject)
    }

    fun hasLabel(subject: String): Boolean {
        val s = iri(subject)
        return listOf(NS.SKOS + "prefLabel", NS.RDFS + "label").any { objects(s, it).isNotEmpty() }
    }
}

/** What follows the last `#` or `/` of an IRI. */
fun localName(iri: String): String {
    if ('#' in iri) return iri.substringAfterLast('#').ifEmpty { iri }
    return iri.trimEnd('/').substringAfterLast('/').ifEmpty { iri }
}

/** A number as la-gram writes one into SVG: rounded to two decimals, without a trailing `.0`. */
fun fmt(value: Double): String {
    val rounded = (value * 100).roundToLong() / 100.0
    if (rounded == 0.0) return "0"
    return if (abs(rounded - rounded.roundToLong()) < 1e-9) rounded.roundToLong().toString()
    else rounded.toBigDecimal().stripTrailingZeros().toPlainString()
}

fun round2(value: Double): Double = (value * 100).roundToLong() / 100.0

/** Text for an XML attribute value or element content. */
fun xml(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
