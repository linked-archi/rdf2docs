package archi.linked.rdf2docs

import archi.linked.rdf2docs.notation.Defaults
import archi.linked.rdf2docs.notation.IconResolver
import archi.linked.rdf2docs.notation.NS
import archi.linked.rdf2docs.notation.NotationPack
import archi.linked.rdf2docs.notation.NotationStyles
import archi.linked.rdf2docs.notation.Published
import archi.linked.rdf2docs.notation.Shapes
import archi.linked.rdf2docs.notation.TypeGroup
import archi.linked.rdf2docs.notation.localName
import archi.linked.rdf2docs.notation.xml
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The files every page shares, in `_assets/` at the site's root: the style sheet and scripts, the libraries the
 * graph, SPARQL and RDF pages run (vendored, so that the site works offline and from disk), and the data the pages
 * read. The notation's icons are not among them: the pages show them where meta.linked.archi publishes them.
 *
 * `_assets` starts with an underscore so that it cannot be the directory of a resource: DD-41 mints no IRI whose
 * first segment under the base does.
 */
object SiteAssets {
    const val DIR = "_assets"

    private val STATIC = listOf(
        "site/style.css" to "style.css",
        "site/js/site.js" to "$DIR/js/site.js",
        "site/js/tree.js" to "$DIR/js/tree.js",
        "site/js/diagram.js" to "$DIR/js/diagram.js",
        "site/js/graph.js" to "$DIR/js/graph.js",
        "site/js/graph.css" to "$DIR/js/graph.css",
        "site/js/sparql.js" to "$DIR/js/sparql.js",
        "site/js/rdf.js" to "$DIR/js/rdf.js",
        "site/vendor/d3-7.9.0.min.js" to "$DIR/vendor/d3-7.9.0.min.js",
        "site/vendor/cytoscape-3.34.3.min.js" to "$DIR/vendor/cytoscape-3.34.3.min.js",
        "site/vendor/oxigraph-0.5.11.js" to "$DIR/vendor/oxigraph-0.5.11.js",
        "site/vendor/oxigraph-0.5.11-wasm.js" to "$DIR/vendor/oxigraph-0.5.11-wasm.js",
        "site/vendor/LICENSES.md" to "$DIR/vendor/LICENSES.md",
    )

    /** Copies the shared files into [outputDir]. */
    fun copyStatic(outputDir: File) {
        for ((resource, target) in STATIC) {
            val stream = SiteAssets::class.java.classLoader.getResourceAsStream(resource) ?: error("missing resource $resource")
            val file = File(outputDir, target)
            file.parentFile.mkdirs()
            stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
    }

    /** A JavaScript string literal of [s], safe inside a `<script>` element. */
    fun js(s: String): String {
        val sb = StringBuilder(s.length + 16).append('"')
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\"); '"' -> sb.append("\\\""); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            '<' -> sb.append("\\u003c"); '>' -> sb.append("\\u003e"); '&' -> sb.append("\\u0026")
            ' ' -> sb.append("\\u2028"); ' ' -> sb.append("\\u2029")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** Writes `_assets/data/{name}.js`, setting `window.{variable}` to [json]: a classic script, so it loads from disk too. */
    fun writeData(outputDir: File, name: String, variable: String, json: String) {
        val file = File(outputDir, "$DIR/data/$name.js")
        file.parentFile.mkdirs()
        file.writeText("window.$variable = $json;\n")
    }
}

/**
 * The notation's icons, where they are published: an icon, a glyph or a picture of the notation is an IRI under
 * https://meta.linked.archi/, which serves it; a page shows it there. Nothing is copied into the site.
 */
class SiteIcons : IconResolver {
    override fun href(iconIri: String): String? {
        val value = iconIri.trim()
        if (value.startsWith("data:")) return value
        // One meta.linked.archi does not serve is not drawn: a badge then, as la-gram's, is left out.
        return if ((value.startsWith("https://") || value.startsWith("http://")) && Published.serves(value)) value else null
    }
}

/** How a type is shown in the lists, the tree, the legends and the graph, all from its notation. */
data class TypeLook(
    val iri: String,
    val label: String,
    val relationship: Boolean,
    /** The icon beside a name: HTML showing the notation's own pictures, at their published IRIs. */
    val iconHtml: String,
    /** The glyph the diagram's badge shows (`arch-vis:iconSymbol`), as published. */
    val glyph: String?,
    /** The picture the notation publishes of the type (`arch:prefVisNotation`): its whole figure. */
    val picture: String?,
    val fill: String?,
    val line: String?,
    /** The core shape it is drawn as — a notation's own shape's fallback. */
    val shape: String?,
    /** How it is drawn: `ShapeWithBadge`, `IconCentric` or `ShapeOnly`. */
    val mode: String,
    val width: Double,
    val height: Double,
    val lineStyle: String?,
    val sourceDecor: String?,
    val targetDecor: String?,
    val group: TypeGroup?,
    val language: String?,
)

/**
 * Each type's look for the lists, the tree and the graph, from the default presentation context's notation. Its icon
 * is what meta.linked.archi publishes for it, as published: the type's picture (`arch:prefVisNotation`, its whole
 * figure); else its glyph (`arch-vis:iconSymbol`) alone; else none. A relationship without a picture is its whole
 * line, drawn from its notation ([Shapes.relationshipIconDataUri]). A view has the tree's mark for one, ▦.
 */
class TypeLooks(private val pack: NotationPack, private val styles: NotationStyles) {
    private val looks = ConcurrentHashMap<String, TypeLook>()

    fun look(typeIri: String, relationship: Boolean): TypeLook =
        looks.computeIfAbsent(typeIri + if (relationship) "#rel" else "") { build(typeIri, relationship) }

    fun all(): Collection<TypeLook> = looks.values

    private fun language(typeIri: String): String? =
        if (typeIri.startsWith(NS.META)) typeIri.removePrefix(NS.META).substringBefore('/') else null

    private val VIEW_TYPES = setOf("View", "Diagram", "Catalog", "Matrix")

    /** An icon's IRI, when it is one meta.linked.archi serves: one it does not is passed over for the next choice. */
    private fun published(iri: String?) = iri?.trim()?.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && Published.serves(it) }

    private fun build(typeIri: String, relationship: Boolean): TypeLook {
        val label = pack.typeLabel(typeIri) ?: localName(typeIri)
        val picture = published(styles.pictureFor(typeIri))
        val title = xml(label)
        if (relationship) {
            val rs = styles.relationshipStyleFor(typeIri)
            val line = rs?.lineColor ?: Defaults.LINE
            // Its picture, else the whole line drawn from its notation — its style, colour and both ends' decorations.
            val icon = if (picture != null) icon(picture, null, title) else {
                val drawn = Shapes.relationshipIconDataUri(line, rs?.lineStyle, rs?.sourceDecor, rs?.targetDecor)
                "<span class=\"ti bare line\" title=\"$title\"><img src=\"${xml(drawn)}\" alt=\"\"></span>"
            }
            return TypeLook(typeIri, label, true, icon, null, picture, null, line, null, "ShapeOnly", 0.0, 0.0,
                rs?.lineStyle, rs?.sourceDecor, rs?.targetDecor, pack.groupOf(typeIri), language(typeIri))
        }
        if (typeIri.startsWith(NS.ARCH) && localName(typeIri) in VIEW_TYPES) {
            val mark = when (localName(typeIri)) { "Catalog" -> "☰"; "Matrix" -> "▤"; else -> "▦" }
            return TypeLook(typeIri, label, false, "<span class=\"ti mark\" title=\"$title\">$mark</span>", null, null, null, null, null,
                "ShapeOnly", 120.0, 55.0, null, null, null, pack.groupOf(typeIri), language(typeIri))
        }
        val style = styles.styleFor(typeIri)
        val mode = styles.renderingModeFor(typeIri)
        val glyph = published(style?.iconSymbol)
        val fill = style?.fillColor ?: Defaults.FILL
        val line = style?.lineColor ?: Defaults.LINE
        val shape = style?.customShape?.fallback ?: style?.shapeType ?: Defaults.SHAPE_TYPE
        return TypeLook(typeIri, label, false, icon(picture, glyph, title), glyph, picture, fill, line, shape, mode,
            style?.defaultWidth ?: Defaults.WIDTH, style?.defaultHeight ?: Defaults.HEIGHT, null, null, null, pack.groupOf(typeIri), language(typeIri))
    }

    /** The icon: the published picture, else the published glyph alone, else an empty place, so that names line up. */
    private fun icon(picture: String?, glyph: String?, title: String): String = when {
        picture != null -> "<span class=\"ti pic\" title=\"$title\"><img src=\"${xml(picture)}\" alt=\"\"></span>"
        glyph != null -> "<span class=\"ti bare\" title=\"$title\"><img src=\"${xml(glyph)}\" alt=\"\"></span>"
        else -> "<span class=\"ti none\" title=\"$title\"></span>"
    }

    /** A type's icon, beside a name. Its pictures are absolute IRIs, so [back] is not needed; kept for the callers. */
    @Suppress("UNUSED_PARAMETER")
    fun iconHtml(look: TypeLook, back: String = "", cls: String = ""): String = look.iconHtml
}
