package archi.linked.rdf2docs.notation

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Resource

/**
 * What the model says a concept is called and pictured as, for one presentation context — the kernel's
 * `context-names.ts`, `element-pictures.ts` and `label-expression.ts`, which la-gram labels and pictures its shapes
 * with:
 *
 * - a name for an audience is a SKOS-XL label (`skosxl:altLabel` → `skosxl:literalForm`, `arch:inPresentationContext`);
 * - a picture for every audience is `arch:prefVisNotation`, one for an audience an `arch:altVisNotation` whose value
 *   is a `schema:ImageObject` with its `schema:contentUrl` and `arch:inPresentationContext`;
 * - a label expression is text with `{name}`, `{type}` and property-path placeholders (`{prefix:local}`, `{<iri>}`,
 *   steps joined by `/`, `^` for a step backwards, `{{` and `}}` for braces); a line whose placeholders all come to
 *   nothing is left out.
 */
class ModelLabels(private val t: Triples, private val typeLabel: (String) -> String?, private val prefixes: Map<String, String>) {

    /** The canonical name: `skos:prefLabel` in English, else any, else `schema:name`, `rdfs:label`, `dcterms:title`. */
    fun nameOf(iri: String): String? {
        val s = iri(iri)
        for (p in listOf(NS.SKOS + "prefLabel", NS.SCHEMA + "name", NS.RDFS + "label", NS.DCTERMS + "title")) {
            val literals = t.objects(s, p).filterIsInstance<Literal>()
            (literals.firstOrNull { it.language.orElse("") == "en" } ?: literals.firstOrNull())?.let { return it.stringValue() }
        }
        return null
    }

    /** Its name for [context] — in English, else in no language, else in any; null when it has none. */
    fun contextNameOf(iri: String, context: String?): String? {
        if (context == null) return null
        val names = t.objects(iri(iri), NS.SKOSXL + "altLabel").filterIsInstance<Resource>()
            .filter { t.firstString(it, NS.ARCH + "inPresentationContext") == context }
            .flatMap { t.objects(it, NS.SKOSXL + "literalForm").filterIsInstance<Literal>() }
        return (names.firstOrNull { it.language.orElse("") == "en" } ?: names.firstOrNull { !it.language.isPresent } ?: names.firstOrNull())?.stringValue()
    }

    /** What it is called for [context]: its name there, else its canonical name. */
    fun displayName(iri: String, context: String?): String? = contextNameOf(iri, context) ?: nameOf(iri)

    /** Its picture for [context], else its picture for every audience. */
    fun pictureOf(iri: String, context: String?): String? {
        val s = iri(iri)
        fun urlOf(v: org.eclipse.rdf4j.model.Value): String? = when (v) {
            is Literal -> Appearance.imageUri(v.stringValue())
            is Resource -> t.firstString(v, NS.SCHEMA + "contentUrl")?.let { Appearance.imageUri(it) } ?: (v as? IRI)?.let { Appearance.imageUri(it.stringValue()) }
            else -> null
        }
        if (context != null) {
            t.objects(s, NS.ARCH + "altVisNotation").filterIsInstance<Resource>()
                .firstOrNull { t.firstString(it, NS.ARCH + "inPresentationContext") == context }
                ?.let { urlOf(it) }?.let { return it }
        }
        return t.objects(s, NS.ARCH + "prefVisNotation").firstNotNullOfOrNull { urlOf(it) }
    }

    // ── Label expressions ───────────────────────────────────────────────────

    private sealed interface Part
    private data class Text(val text: String) : Part
    private data object Name : Part
    private data object Type : Part
    private data class Path(val steps: List<Pair<String, Boolean>>) : Part
    private data class Error(val source: String) : Part

    /** What [expression] says for [subject] in [context]. */
    fun evaluate(expression: String, subject: String, typeIri: String?, context: String?, language: String = "en"): String {
        val kept = mutableListOf<String>()
        for (line in expression.replace(Regex("\r\n?"), "\n").split('\n')) {
            var text = ""
            var placeholders = 0
            var filled = 0
            for (part in parseLine(line)) {
                when (part) {
                    is Text -> text += part.text
                    is Error -> text += part.source
                    else -> {
                        placeholders++
                        val value = when (part) {
                            is Name -> displayName(subject, context) ?: ""
                            is Type -> typeIri?.let(typeLabel) ?: ""
                            is Path -> pathValues(part.steps, subject, context, language)
                            else -> ""
                        }
                        if (value.isNotEmpty()) filled++
                        text += value
                    }
                }
            }
            if (placeholders > 0 && filled == 0) continue
            kept.add(text)
        }
        return kept.joinToString("\n").trim()
    }

    private fun parseLine(line: String): List<Part> {
        val parts = mutableListOf<Part>()
        var text = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '{' && line.getOrNull(i + 1) == '{') { text.append('{'); i += 2; continue }
            if (c == '}' && line.getOrNull(i + 1) == '}') { text.append('}'); i += 2; continue }
            if (c == '{') {
                var end = -1
                var inIri = false
                for (j in i + 1 until line.length) {
                    when (line[j]) { '<' -> inIri = true; '>' -> inIri = false; '}' -> if (!inIri) { end = j } }
                    if (end >= 0) break
                }
                if (end < 0) { text.append(line.substring(i)); break }
                if (text.isNotEmpty()) { parts.add(Text(text.toString())); text = StringBuilder() }
                parts.add(placeholder(line.substring(i + 1, end).trim(), line.substring(i, end + 1)))
                i = end + 1
                continue
            }
            text.append(c)
            i++
        }
        if (text.isNotEmpty()) parts.add(Text(text.toString()))
        return parts
    }

    private fun placeholder(content: String, source: String): Part {
        if (content == "name") return Name
        if (content == "type") return Type
        if (content.isEmpty()) return Error(source)
        val steps = mutableListOf<Pair<String, Boolean>>()
        var rest = content
        while (rest.isNotEmpty()) {
            var inverse = false
            if (rest.startsWith("^")) { inverse = true; rest = rest.substring(1).trimStart() }
            val token: String
            if (rest.startsWith("<")) {
                val end = rest.indexOf('>')
                if (end < 0) return Error(source)
                token = rest.substring(0, end + 1)
                rest = rest.substring(end + 1).trimStart()
            } else {
                val slash = rest.indexOf('/')
                token = (if (slash < 0) rest else rest.substring(0, slash)).trim()
                rest = if (slash < 0) "" else rest.substring(slash)
            }
            val iri = resolve(token) ?: return Error(source)
            steps.add(iri to inverse)
            if (rest.startsWith("/")) {
                rest = rest.substring(1).trimStart()
                if (rest.isEmpty()) return Error(source)
            } else if (rest.isNotEmpty()) return Error(source)
        }
        return Path(steps)
    }

    private fun resolve(token: String): String? {
        if (token.startsWith("<") && token.endsWith(">")) return token.substring(1, token.length - 1).trim().takeIf { Regex("^[a-z][\\w+.-]*:\\S+$", RegexOption.IGNORE_CASE).matches(it) }
        val colon = token.indexOf(':')
        if (colon < 0 || token == "name" || token == "type") return null
        val ns = prefixes[token.substring(0, colon)] ?: return null
        return ns + token.substring(colon + 1)
    }

    private fun pathValues(steps: List<Pair<String, Boolean>>, subject: String, context: String?, language: String): String {
        var frontier: List<org.eclipse.rdf4j.model.Value> = listOf(iri(subject))
        for ((predicate, inverse) in steps) {
            frontier = frontier.filterIsInstance<Resource>().flatMap { v ->
                if (inverse) t.subjects(predicate, v) else t.objects(v, predicate)
            }
        }
        val literals = frontier.filterIsInstance<Literal>()
        val preferred = literals.filter { !it.language.isPresent || it.language.get() == language }
        val shown = (frontier.filterIsInstance<IRI>().map { displayName(it.stringValue(), context) ?: localName(it.stringValue()) } +
            (preferred.ifEmpty { literals }).map { it.stringValue() }).filter { it.isNotEmpty() }.distinct()
        return if (shown.size > 20) shown.take(20).joinToString(", ") + ", …" else shown.joinToString(", ")
    }
}
