package archi.linked.rdf2docs

import java.io.ByteArrayOutputStream

/**
 * Where a resource's page is written, and why a resource cannot have one.
 *
 * A page is the resource's IRI relative to `--base`, so that the site, served at the base IRI, answers the IRI
 * itself (IRI Minting, *Static publishing*). That path is needed in two forms. Links use the IRI's own, escapes
 * and all. The directory on disk is the decoded form, because a web server, and a browser reading `file:` URLs,
 * decodes the path of a request before it looks for the file: a page for `element/a%20b` must be in a directory
 * called `a b`.
 */
internal object PagePaths {

    /**
     * The longest path *component* the output may contain, in bytes.
     *
     * The filesystem limit is per component rather than per path, and it is counted in bytes rather than
     * characters: 255 on ext4, overlayfs and most others, and 255 UTF-8 characters on APFS. 255 bytes is
     * therefore the portable floor — a segment that fits it fits everywhere — which is what matters for output
     * generated on one machine and served from another.
     *
     * A resource whose IRI exceeds it is reported rather than written, and rather than shortened: the point of
     * this generator is that the URL of a page *is* the IRI of the resource, so a hashed or truncated directory
     * would quietly break the one property the output exists to have.
     */
    const val MAX_SEGMENT_BYTES = 255

    /** The IRI relative to the base, with no slash at either end. */
    fun relative(iri: String, baseIri: String): String = iri.removePrefix(baseIri).trim('/')

    /** The page's directory as a URL path from the site root, ending in `/`, or empty for the root. */
    fun urlDir(iri: String, baseIri: String): String {
        val relative = relative(iri, baseIri)
        return if (relative.isEmpty()) "" else "$relative/"
    }

    /** The page's directory on disk: [urlDir] with each segment decoded. Check [unwritableReason] first. */
    fun diskDir(iri: String, baseIri: String): String {
        val relative = relative(iri, baseIri)
        return if (relative.isEmpty()) "" else relative.split('/').joinToString("/", postfix = "/") { decode(it) }
    }

    /** How many directories below the site root the page of [iri] is. */
    fun depth(iri: String, baseIri: String): Int {
        val relative = relative(iri, baseIri)
        return if (relative.isEmpty()) 0 else relative.count { it == '/' } + 1
    }

    /**
     * Why [iri] cannot have a page of its own, or null when it can.
     *
     * Each case would otherwise write somewhere the IRI does not lead: into the index page, into a directory
     * whose name a browser reads as a fragment, or outside the output directory altogether.
     */
    fun unwritableReason(iri: String, baseIri: String): String? {
        val relative = relative(iri, baseIri)
        if (relative.isEmpty()) {
            return "the IRI is the base IRI, whose page is the index"
        }
        if ('#' in relative) {
            return "the IRI has a fragment, and a page is written at a path; a fragment is an anchor on the page " +
                "of the IRI before it"
        }
        val segments = relative.split('/').map { decode(it) }
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return "the IRI path has an empty, '.' or '..' segment, which cannot be a directory of its own"
        }
        if (segments.any { s -> s.any { it == '/' || it == '\\' || it == '\u0000' } }) {
            return "an IRI path segment decodes to '/', '\\' or NUL, which cannot be in a directory name"
        }
        val longest = segments.maxOf { it.toByteArray(Charsets.UTF_8).size }
        return if (longest <= MAX_SEGMENT_BYTES) null
        else "IRI path segment is $longest bytes, over the $MAX_SEGMENT_BYTES-byte filesystem limit for one directory name"
    }

    /**
     * Decodes the `%XX` escapes of one IRI path segment as UTF-8.
     *
     * Not `URLDecoder`, which decodes `application/x-www-form-urlencoded` and so turns `+` into a space: in an
     * IRI path a `+` is a `+`. A `%` not followed by two hex digits is kept as it is.
     */
    fun decode(segment: String): String {
        if ('%' !in segment) return segment
        val out = ByteArrayOutputStream(segment.length)
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%' && i + 2 < segment.length && isHex(segment[i + 1]) && isHex(segment[i + 2])) {
                out.write(segment.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                // One code point at a time, so that a character outside the BMP is not split into halves.
                val end = i + Character.charCount(segment.codePointAt(i))
                out.write(segment.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}

/**
 * The pages of one run, and what a link to any IRI under the base opens.
 *
 * Only some resources have a page ([HtmlPageGenerator.querySubjects]). A shape is a fragment of its view's page,
 * and a part of a resource — a property pair, a tag, a route cell — is shown on its owner's page, so a link to
 * either goes there. [partOwner] holds that owner for every part, found by the owner rule of DD-41: a part is
 * named `{owner}/{part}` and a shape `{view}#{shape}`, so the owner is the closest page an IRI extends.
 */
internal class Site(
    val baseIri: String,
    val pages: Map<String, HtmlPageGenerator.SubjectInfo>,
    private val partOwner: Map<String, String>,
) {
    private val partsByOwner: Map<String, List<String>> =
        partOwner.entries.groupBy({ it.value }, { it.key }).mapValues { (_, parts) -> parts.sorted() }

    /** The parts and shapes shown on the page of [owner], sorted by IRI. */
    fun partsOf(owner: String): List<String> = partsByOwner[owner].orEmpty()

    /** The page that shows [iri], when it is a part or a shape rather than a page of its own. */
    fun ownerOf(iri: String): String? = partOwner[iri]

    /**
     * Where a link to [iri] goes from a page whose way back to the site root is [back] (`../../`), or null when
     * nothing in the site shows it.
     *
     * An IRI outside the base is linked as it is. A shape keeps its fragment, `view/{v}/index.html#node-{id}`,
     * since its view's page gives every shape its fragment as `id`.
     */
    fun href(iri: String, back: String): String? {
        if (!iri.startsWith(baseIri)) return iri
        if (iri in pages) return back + PagePaths.urlDir(iri, baseIri) + "index.html"
        val hash = iri.indexOf('#')
        if (hash >= 0) {
            val document = iri.substring(0, hash)
            if (document in pages) return back + PagePaths.urlDir(document, baseIri) + "index.html" + iri.substring(hash)
        }
        return partOwner[iri]?.let { back + PagePaths.urlDir(it, baseIri) + "index.html" }
    }

    companion object {
        /** The kinds of page that show parts of their own. A former name or a provenance record has none. */
        private val OWNER_KINDS = setOf(
            HtmlPageGenerator.ResourceKind.VIEW,
            HtmlPageGenerator.ResourceKind.RELATIONSHIP,
            HtmlPageGenerator.ResourceKind.ELEMENT,
            HtmlPageGenerator.ResourceKind.MODEL,
            HtmlPageGenerator.ResourceKind.DOCUMENTATION,
        )

        /**
         * The owner of each subject in [unpaged]: the closest page of an owner kind whose IRI the subject's
         * extends by `/…` or `#…`. A subject with none is left out, and is shown nowhere.
         *
         * The search climbs past IRIs that are not pages, so an element's tag, `{element}/tag/{digest}`, reaches
         * the element, and everything in a view's fragment, a route cell `{view}#link-{id}-points-0` included,
         * reaches the view at its `#`. It stops at a former name: a model's IRI from before DD-41,
         * `{base}{notation}/{modelId}`, is a prefix of every IRI in the model, and is not their owner.
         */
        fun owners(
            unpaged: Collection<String>,
            pages: Map<String, HtmlPageGenerator.SubjectInfo>,
            baseIri: String,
        ): Map<String, String> {
            val owners = HashMap<String, String>()
            for (iri in unpaged) {
                var current = iri
                while (true) {
                    val cut = maxOf(current.lastIndexOf('/'), current.lastIndexOf('#'))
                    if (cut < baseIri.length) break
                    current = current.substring(0, cut)
                    val page = pages[current] ?: continue
                    if (page.kind in OWNER_KINDS) owners[iri] = current
                    break
                }
            }
            return owners
        }
    }
}
