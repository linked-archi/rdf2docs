package archi.linked.rdf2docs.notation

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.slf4j.LoggerFactory

/** A presentation context a loaded language offers: an audience a view is drawn for. */
data class PresentationContext(val iri: String, val label: String, val metamodel: String?)

/** A loaded language's manifest: what it is drawn with. */
data class Metamodel(val iri: String, val label: String, val notationSet: String?, val contextScheme: String?)

/** A group a type is listed in: its domain, layer or category in its language's taxonomy. */
data class TypeGroup(val iri: String, val label: String, val order: Int)

/**
 * The notation pack the generator draws with: the languages of `src/main/resources/notation/`, laid out as
 * https://meta.linked.archi/ is published and refreshed from linked-archi-meta by `scripts/sync-notation.sh`.
 *
 * Only the languages a model uses are loaded: a language is used when an IRI the model names — a type, the metamodel
 * it conforms to — is in the language's published folder, `https://meta.linked.archi/{language}/`. The core is always
 * loaded: its notation draws the core's own relationship types whatever the language. The languages the model says
 * it conforms to come first, in the order it says so, then the others; that order is the load order the notation
 * lookup's fallback layers follow.
 */
class NotationPack private constructor(val model: Model, val packIds: List<String>, private val declared: List<String>) {

    val t = Triples(model)

    /** Every loaded language's manifest: the model's own metamodels first, in its order, then the rest by IRI. */
    val metamodels: List<Metamodel> by lazy {
        val all = t.subjectsOfType(NS.ARCH + "Metamodel").filterIsInstance<IRI>().map { m ->
            Metamodel(
                m.stringValue(),
                t.labelOf(m.stringValue()),
                t.objects(m, NS.ARCH + "notationSet").filterIsInstance<IRI>().firstOrNull()?.stringValue(),
                t.objects(m, NS.ARCH + "presentationContextScheme").filterIsInstance<IRI>().firstOrNull()?.stringValue(),
            )
        }
        val rank = declared.withIndex().associate { it.value to it.index }
        all.sortedWith(compareBy<Metamodel> { rank[it.iri] ?: Int.MAX_VALUE }.thenBy { it.iri })
    }

    /** The metamodel the model is drawn with when no presentation context says otherwise. */
    val primary: Metamodel? get() = metamodels.firstOrNull { it.notationSet != null }

    /** The presentation contexts of the loaded languages, the model's own languages first. */
    val contexts: List<PresentationContext> by lazy {
        metamodels.flatMap { m ->
            val scheme = m.contextScheme ?: return@flatMap emptyList()
            val tops = t.objects(iri(scheme), NS.SKOS + "hasTopConcept").filterIsInstance<IRI>().map { it.stringValue() }
            val inScheme = t.subjects(NS.SKOS + "inScheme", iri(scheme)).filterIsInstance<IRI>().map { it.stringValue() }
            (tops + inScheme).distinct().map { PresentationContext(it, t.labelOf(it), m.iri) }
        }.distinctBy { it.iri }
    }

    /** Direct `rdfs:subClassOf` parents of every class, for the notation lookup's inheritance step. */
    val superClassMap: Map<String, List<String>> by lazy {
        val map = HashMap<String, MutableList<String>>()
        model.filter(null, iri(NS.RDFS + "subClassOf"), null).forEach { st ->
            val s = st.subject as? IRI ?: return@forEach
            val o = st.`object` as? IRI ?: return@forEach
            val parents = map.getOrPut(s.stringValue()) { mutableListOf() }
            if (o.stringValue() !in parents) parents.add(o.stringValue())
        }
        map
    }

    /** The published symbol of each decoration (`arch-vis:decorSymbol`). */
    val decorationSymbols: Map<String, String> by lazy { Notation.decorationSymbols(t) }

    private val sets = HashMap<String, NotationSet?>()

    fun notationSet(iri: String): NotationSet? = synchronized(sets) { sets.getOrPut(iri) { Notation.buildVisualNotations(t, iri) } }

    /** The core notation's sets, consulted after every language's default set. */
    val coreSets: List<String> by lazy {
        t.subjectsOfType(NS.VIS + "NotationSet").filterIsInstance<IRI>().map { it.stringValue() }
            .filter { it.startsWith(NS.CORE_NOTATION) }.distinct().sorted()
    }

    private val registries = HashMap<String, NotationStyles>()

    /** How everything is drawn for [context] — or, null, with each language's default notation. */
    fun styles(context: String?): NotationStyles = synchronized(registries) {
        registries.getOrPut(context ?: "") { NotationStyles(this, context) }
    }

    /** A type's name in its language, or null when no loaded language names it. */
    fun typeLabel(typeIri: String): String? = if (t.hasLabel(typeIri)) t.labelOf(typeIri) else null

    /** Whether a loaded language declares [typeIri] (it has a label or a parent there). */
    fun knows(typeIri: String): Boolean = t.hasLabel(typeIri) || superClassMap.containsKey(typeIri)

    /** Every superclass of [typeIri], nearest first. */
    fun ancestors(typeIri: String): List<String> = ClassHierarchy.superClassesNearestFirst(typeIri, superClassMap, emptySet())

    private val groupCache = HashMap<String, TypeGroup?>()

    /**
     * The group [typeIri] is listed in: a concept of its language's taxonomy that has it as `skos:narrower` — its
     * domain, layer or relationship category. Where several do, the one an ordered collection of layers or domains
     * lists is chosen, then one any ordered collection lists, then the first by name; the group's order is its place
     * in that collection. A type its own taxonomy does not place inherits the group of its nearest superclass.
     */
    fun groupOf(typeIri: String): TypeGroup? = synchronized(groupCache) {
        groupCache.getOrPut(typeIri) { (listOf(typeIri) + ancestors(typeIri)).firstNotNullOfOrNull { directGroup(it) } }
    }

    private val collections: List<Pair<String, List<String>>> by lazy {
        t.subjectsOfType(NS.SKOS + "OrderedCollection").mapNotNull { c ->
            val list = t.first(c, NS.SKOS + "memberList") as? Resource ?: return@mapNotNull null
            t.labelOf(c.stringValue()) to rdfList(list)
        }
    }

    private fun rdfList(head: Resource): List<String> {
        val items = mutableListOf<String>()
        var cell: Resource? = head
        val seen = HashSet<Resource>()
        while (cell != null && cell != RDF.NIL && seen.add(cell)) {
            t.first(cell, RDF.FIRST.stringValue())?.stringValue()?.let { items.add(it) }
            cell = t.first(cell, RDF.REST.stringValue()) as? Resource
        }
        return items
    }

    private fun directGroup(typeIri: String): TypeGroup? {
        val candidates = (t.subjects(NS.SKOS + "narrower", iri(typeIri)) + t.subjects("http://purl.org/iso25964/skos-thes#narrowerPartitive", iri(typeIri)))
            .filterIsInstance<IRI>().map { it.stringValue() }.distinct()
        if (candidates.isEmpty()) return null
        fun placed(preferred: Boolean): TypeGroup? {
            for ((label, members) in collections) {
                if (preferred && !(label.contains("Layer", true) || label.contains("Domain", true) || label.contains("Categor", true))) continue
                val hit = candidates.firstOrNull { it in members } ?: continue
                return TypeGroup(hit, t.labelOf(hit), members.indexOf(hit))
            }
            return null
        }
        return placed(true) ?: placed(false) ?: candidates.map { TypeGroup(it, t.labelOf(it), 1000) }.minBy { it.label }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotationPack::class.java)
        private const val ROOT = "notation/"

        /** The manifest: one line per language, its id then its files. */
        private val manifest: List<Pair<String, List<String>>> by lazy {
            val text = NotationPack::class.java.classLoader.getResourceAsStream(ROOT + "packs.txt")?.bufferedReader()?.readText() ?: ""
            text.lines().filter { it.isNotBlank() }.map { line ->
                val parts = line.trim().split(Regex("\\s+"))
                parts.first() to parts.drop(1)
            }
        }

        /**
         * The pack for a model: the languages its IRIs are in. [modelIris] are the types and metamodels the model
         * names; [declared] the metamodels it says it conforms to, in its order.
         */
        fun load(modelIris: Set<String>, declared: List<String>): NotationPack {
            val used = manifest.map { it.first }.filter { id ->
                id == "core" || modelIris.any { it.startsWith(NS.META + id + "/") }
            }
            // The model's own languages first, in the order it declares them; then the rest, in the manifest's order.
            val declaredIds = declared.mapNotNull { m -> used.firstOrNull { m.startsWith(NS.META + it + "/") } }
            val order = (listOf("core") + declaredIds + used).distinct()
            val model = LinkedHashModel()
            for (id in order) {
                val files = manifest.firstOrNull { it.first == id }?.second ?: continue
                for (file in files) {
                    val stream = NotationPack::class.java.classLoader.getResourceAsStream(ROOT + file) ?: continue
                    try {
                        stream.use { model.addAll(Rio.parse(it, NS.META + file, RDFFormat.TURTLE)) }
                    } catch (e: Exception) {
                        log.warn("Notation pack: could not read {}: {}", file, e.message)
                    }
                }
            }
            log.info("Notation pack: {} ({} statements)", order.joinToString(", "), model.size)
            return NotationPack(model, order, declared)
        }
    }
}

/** The class hierarchy walk the notation lookup inherits through. */
object ClassHierarchy {
    /** The notation guide stops the walk at the roots every element and relationship class reaches. */
    val NOTATION_ROOTS = setOf(NS.ARCH + "Element", NS.ARCH + "QualifiedRelationship")

    /** Superclasses of [classIri] breadth-first: by distance, then by IRI; not past a root in [stopAt]. */
    fun superClassesNearestFirst(classIri: String, map: Map<String, List<String>>, stopAt: Set<String> = NOTATION_ROOTS): List<String> {
        if (classIri in stopAt) return emptyList()
        val visited = hashSetOf(classIri)
        val ordered = mutableListOf<String>()
        var frontier = listOf(classIri)
        while (frontier.isNotEmpty()) {
            val level = mutableListOf<String>()
            for (current in frontier) {
                if (current != classIri && current in stopAt) continue
                for (parent in map[current].orEmpty()) if (visited.add(parent)) level.add(parent)
            }
            level.sort()
            ordered.addAll(level)
            frontier = level
        }
        return ordered
    }
}

/**
 * How each type is drawn for one presentation context: the notation lookup of the kernel's `notation-resolver.ts`,
 * which the palette and the canvas both use, and the registry la-gram's renderer asks (`notation-styles.ts`).
 *
 * For a class C the active set A is the set the context names (`arch:usesNotationSet`), else the primary metamodel's
 * default set; the fallback layers are every loaded language's default set but A, in load order, then the core
 * notation's. In order: a notation for C in A; one in a fallback layer; C's own `arch:prefVisNotation`; the nearest
 * superclass's in A, then in the layers; nothing, and the renderer's defaults.
 */
class NotationStyles(val pack: NotationPack, val context: String?) {
    val activeSet: String? = context?.let { Notation.notationSetForContext(pack.t, it) } ?: pack.primary?.notationSet
    private val active: NotationSet? = activeSet?.let { pack.notationSet(it) }
    private val fallbacks: List<NotationSet> =
        (pack.metamodels.mapNotNull { it.notationSet } + pack.coreSets).distinct().filter { it != activeSet }.mapNotNull { pack.notationSet(it) }

    data class Resolved(val notation: VisualNotation?, val prefVisNotation: String?, val source: String)

    private val cache = HashMap<String, Resolved>()

    fun resolve(classIri: String): Resolved = synchronized(cache) { cache.getOrPut(classIri) { lookup(classIri) } }

    private fun lookup(classIri: String): Resolved {
        val own = pack.t.objects(iri(classIri), NS.ARCH + "prefVisNotation").firstOrNull()?.stringValue()
        active?.notations?.get(classIri)?.let { return Resolved(it, it.prefVisNotation ?: own, "active") }
        for (set in fallbacks) set.notations[classIri]?.let { return Resolved(it, it.prefVisNotation ?: own, "fallback") }
        if (own != null) return Resolved(null, own, "pref-vis-notation")
        for (sup in ClassHierarchy.superClassesNearestFirst(classIri, pack.superClassMap)) {
            active?.notations?.get(sup)?.let { return Resolved(it, it.prefVisNotation, "active-superclass") }
            for (set in fallbacks) set.notations[sup]?.let { return Resolved(it, it.prefVisNotation, "fallback-superclass") }
        }
        return Resolved(null, null, "default")
    }

    fun styleFor(typeIri: String?): VisualStyle? = typeIri?.let { resolve(it).notation?.style }

    fun relationshipStyleFor(typeIri: String?): RelationshipStyle? = typeIri?.let { resolve(it).notation?.relationshipStyle }

    fun renderingModeFor(typeIri: String?): String = Notation.effectiveRenderingMode(styleFor(typeIri))

    /** The picture a type's notation publishes (`arch:prefVisNotation`). */
    fun pictureFor(typeIri: String?): String? = typeIri?.let { resolve(it).prefVisNotation }

    /** Whether anything is published for the type itself, rather than inherited or the defaults. */
    fun describes(typeIri: String): Boolean = resolve(typeIri).source in setOf("active", "fallback", "pref-vis-notation")

    /** What the context says every label says, where nothing else does. */
    val contextLabelExpression: String? =
        context?.let { Notation.labelExpressionIn(Notation.labelExpressionsOf(pack.t, iri(it)), it) }

    /** What the label of an element of [typeIri] says without one of its own. */
    fun labelExpressionFor(typeIri: String?): String? =
        Notation.labelExpressionIn(styleFor(typeIri)?.labelExpressions, context) ?: contextLabelExpression

    /**
     * The shape a placement of [typeIri] is drawn as when its own style names [shapeIri]: its notation's default, one
     * of its alternatives, or a core shape; null for a shape the notation does not offer.
     */
    fun shapeFor(typeIri: String?, shapeIri: String): Pair<String?, CustomShape?>? {
        val style = styleFor(typeIri)
        for (candidate in listOfNotNull(style) + style?.alternatives.orEmpty()) {
            if (candidate.customShape?.iri == shapeIri) return candidate.shapeType to candidate.customShape
            if (candidate.customShape == null && candidate.shapeType != null && shapeIri == NS.VIS + candidate.shapeType) return candidate.shapeType to null
        }
        val local = if (shapeIri.startsWith(NS.VIS)) shapeIri.removePrefix(NS.VIS) else null
        return if (local != null && local in SHAPE_TYPES) local to null else null
    }

    /** Where a decoration's published symbol is. */
    fun decorationSymbolFor(decoration: String): String? = pack.decorationSymbols[decoration]
}
