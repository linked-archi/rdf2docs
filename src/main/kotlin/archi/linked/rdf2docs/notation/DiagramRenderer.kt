package archi.linked.rdf2docs.notation

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.vocabulary.RDF
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where an icon's file is in the site, from its root; null when it cannot be drawn. */
fun interface IconResolver {
    fun href(iconIri: String): String?
}

/** A view drawn: the `<svg>`, the shapes it drew, and the area it covers. */
data class RenderedDiagram(val svg: String, val drawn: Set<String>, val width: Double, val height: Double)

/**
 * Draws a view as la-gram's canvas does (`ui/canvas/rdf-renderer.ts`), as SVG text: each element in its notation —
 * its shape, fill, line, badge, label — with its own look in the view over the notation's, each figure (a group, a
 * note, a shape, a reference to another view) as la-gram draws figures, nested shapes inside their containers with
 * the container's name in its top band, and every line above every shape, docked where it meets the outline drawn,
 * in its notation's line style with its notation's end decorations.
 *
 * A presentation context changes what is drawn: the notation set it uses, the names and pictures the model gives
 * that audience, the label expressions for it, and the looks a node or line is given for it.
 *
 * Shapes stay in the view's own coordinates (the `viewBox` starts where the drawing does), each in a group moved to
 * its place, so the geometry is la-gram's shape-local geometry.
 */
class DiagramRenderer(
    private val t: Triples,
    private val pack: NotationPack,
    private val icons: IconResolver,
    private val labels: ModelLabels,
    private val types: TypeChooser,
) {
    private data class Point(val x: Double, val y: Double, val fragment: String? = null)

    private data class Node(
        val iri: String, val fragment: String?, val elementIri: String?, val figure: String?, val figureText: String?,
        val figureShape: String?, val referencedView: String?, val bounds: Box, val parent: String?, val own: Appearance?,
        val contextLooks: Map<String, Appearance>, val labelExpressions: List<LabelExpression>,
    )

    private data class Link(
        val iri: String, val fragment: String?, val relationshipIri: String?, val source: String, val target: String,
        val points: List<Point>, val own: Appearance?, val contextLooks: Map<String, Appearance>, val text: String?, val labelBox: Box?,
    )

    private data class View(val nodes: List<Node>, val links: List<Link>)

    private val views = ConcurrentHashMap<String, View>()

    /** Whether the view has anything with a place to be drawn. */
    fun hasLayout(viewIri: String) = read(viewIri).nodes.isNotEmpty()

    private fun read(viewIri: String): View = views.getOrPut(viewIri) { readView(viewIri) }

    private fun fragmentOf(shape: String, viewIri: String) = if (shape.startsWith("$viewIri#")) shape.substring(viewIri.length + 1) else null

    private fun number(subject: Resource, key: String): Double? = (t.first(subject, NS.VIS + key) as? Literal)?.stringValue()?.toDoubleOrNull()

    private fun styles(subject: Resource): Pair<Appearance?, Map<String, Appearance>> {
        var own: Appearance? = null
        val byContext = linkedMapOf<String, Appearance>()
        for (style in t.objects(subject, NS.VIS + "style").filterIsInstance<Resource>()) {
            val context = t.firstString(style, NS.ARCH + "inPresentationContext")
            val look = Appearance.read(t, style).takeUnless { it.isEmpty() } ?: continue
            if (context == null) own = look else byContext[context] = look
        }
        return own to byContext
    }

    private fun readView(viewIri: String): View {
        val members = t.subjects(NS.VIS + "view", iri(viewIri)).filterIsInstance<IRI>()
        val linkLabels = t.model.filter(null, iri(NS.VIS + "label"), null).mapNotNull { it.`object` as? Resource }.toSet()
        val nodes = mutableListOf<Node>()
        val links = mutableListOf<Link>()
        for (s in members) {
            val types = t.types(s)
            if (NS.VIS + "Link" in types) {
                val source = (t.first(s, NS.VIS + "source") as? IRI)?.stringValue() ?: continue
                val target = (t.first(s, NS.VIS + "target") as? IRI)?.stringValue() ?: continue
                val (own, looks) = styles(s)
                val label = t.first(s, NS.VIS + "label") as? Resource
                val labelBox = label?.let { l ->
                    val x = number(l, "bounds-x"); val y = number(l, "bounds-y")
                    if (x == null || y == null) null else Box(x, y, number(l, "bounds-w") ?: 0.0, number(l, "bounds-h") ?: 0.0)
                }
                val relationship = (t.first(s, NS.VIS + "archRelationship") as? IRI)?.stringValue()
                links.add(Link(s.stringValue(), fragmentOf(s.stringValue(), viewIri), relationship, source, target, points(s, viewIri), own, looks,
                    if (relationship == null) t.firstString(s, NS.SKOS + "prefLabel") else null, labelBox))
                continue
            }
            val x = number(s, "bounds-x") ?: continue
            val y = number(s, "bounds-y") ?: continue
            if (s in linkLabels) continue
            val element = (t.first(s, NS.VIS + "archElement") as? IRI)?.stringValue()
            val (own, looks) = styles(s)
            val text = t.firstString(s, NS.SKOS + "prefLabel")
            val styleShape = t.objects(s, NS.VIS + "style").filterIsInstance<Resource>()
                .firstNotNullOfOrNull { (t.first(it, NS.VIS + "shapeType") as? IRI)?.stringValue() }
            val figure = when {
                element != null -> null
                NS.VIS + "ViewReferenceNode" in types -> "reference"
                NS.VIS + "LabelNode" in types -> "note"
                NS.VIS + "ContainerNode" in types || NS.VIS + "Node" in types -> if (styleShape != null) "shape" else "group"
                else -> continue
            }
            val expressions = t.objects(s, NS.VIS + "style").filterIsInstance<Resource>().flatMap { Notation.labelExpressionsOf(t, it) } +
                Notation.labelExpressionsOf(t, s)
            nodes.add(Node(
                s.stringValue(), fragmentOf(s.stringValue(), viewIri), element, figure, text, styleShape,
                (t.first(s, NS.VIS + "referencesView") as? IRI)?.stringValue(),
                Box(x, y, number(s, "bounds-w") ?: Defaults.WIDTH, number(s, "bounds-h") ?: Defaults.HEIGHT),
                (t.first(s, NS.VIS + "inNode") as? IRI)?.stringValue(), own, looks,
                expressions.sortedBy { if (it.context != null) 0 else 1 },
            ))
        }
        return View(nodes, links)
    }

    /** A link's bend points in their order, walking its `arch-vis:points` list. */
    private fun points(link: Resource, viewIri: String): List<Point> {
        val out = mutableListOf<Point>()
        var cell = t.first(link, NS.VIS + "points") as? Resource
        val seen = HashSet<Resource>()
        while (cell != null && cell != RDF.NIL && seen.add(cell)) {
            val p = t.first(cell, RDF.FIRST.stringValue()) as? Resource
            if (p != null) {
                val x = number(p, "point-x"); val y = number(p, "point-y")
                if (x != null && y != null) out.add(Point(x, y, (p as? IRI)?.let { fragmentOf(it.stringValue(), viewIri) }))
            }
            cell = t.first(cell, RDF.REST.stringValue()) as? Resource
        }
        return out
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    private class Defs(val prefix: String) {
        val markers = linkedMapOf<String, String>()
        val extra = linkedMapOf<String, String>()
    }

    /**
     * The view drawn for [context], or null when nothing in it has a place. [back] leads from the page to the site's
     * root; [hrefOf] gives the page of a concept, relative to the page. With [withIds] each shape carries its fragment
     * as `id`, so that `{view}#node-{id}` opens the page at it; without, as `data-frag`, for a drawing of another
     * context on the same page. [idPrefix] keeps the drawing's own definitions (markers, gradients) apart from
     * another's on the page.
     */
    fun render(viewIri: String, context: String?, back: String, hrefOf: (String) -> String?, withIds: Boolean, idPrefix: String, relationshipNames: Boolean = true): RenderedDiagram? {
        val view = read(viewIri)
        if (view.nodes.isEmpty()) return null
        val styles = pack.styles(context)
        val defs = Defs(idPrefix)
        val byIri = view.nodes.associateBy { it.iri }
        val children = view.nodes.groupBy { it.parent }
        val drawn = linkedSetOf<String>()
        val outlines = HashMap<String, List<Pair<Double, Double>>>()
        val shapes = StringBuilder()

        // Parents before their children, each parent's children in the view's order.
        val ordered = mutableListOf<Node>()
        fun visit(n: Node) { ordered.add(n); children[n.iri].orEmpty().forEach(::visit) }
        view.nodes.filter { it.parent == null || it.parent !in byIri }.forEach(::visit)

        for (n in ordered) {
            drawn.add(n.iri)
            val hasChildShapes = children[n.iri].orEmpty().isNotEmpty()
            val (svg, outline) = if (n.figure != null) drawFigure(n, context, defs, hasChildShapes, back, hrefOf, withIds)
                else drawElement(n, context, styles, defs, hasChildShapes, back, hrefOf, withIds)
            outlines[n.iri] = outline
            shapes.append(svg).append('\n')
        }

        // Lines: from the reference of each end (a node's centre, a line's middle), through the bend points, docked.
        val routes = HashMap<String, List<Point>>()
        val lines = StringBuilder()
        var pending = view.links.filter { (it.source in byIri || view.links.any { l -> l.iri == it.source }) && (it.target in byIri || view.links.any { l -> l.iri == it.target }) }
        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            val next = mutableListOf<Link>()
            for (l in pending) {
                fun ref(end: String): Point? = byIri[end]?.let { Point(it.bounds.x + it.bounds.width / 2, it.bounds.y + it.bounds.height / 2) }
                    ?: routes[end]?.let { midpoint(it) }
                val a = ref(l.source); val b = ref(l.target)
                if (a == null || b == null) { next.add(l); continue }
                val path = listOf(a) + l.points + listOf(b)
                val start = outlines[l.source]?.let { dock(it, path, true) } ?: a
                val end = outlines[l.target]?.let { dock(it, path, false) } ?: b
                val waypoints = listOf(start) + l.points + listOf(end)
                routes[l.iri] = waypoints
                drawn.add(l.iri)
                lines.append(drawLink(l, waypoints, context, styles, defs, back, hrefOf, withIds, relationshipNames)).append('\n')
                progress = true
            }
            pending = next
        }

        // The drawing's extent: its shapes, its routes and their names.
        val xs = mutableListOf<Double>(); val ys = mutableListOf<Double>()
        view.nodes.forEach { xs += it.bounds.x; xs += it.bounds.x + it.bounds.width; ys += it.bounds.y; ys += it.bounds.y + it.bounds.height }
        routes.values.flatten().forEach { xs += it.x; ys += it.y }
        val pad = 20.0
        val minX = xs.min() - pad; val minY = ys.min() - pad
        val w = xs.max() - minX + pad; val h = ys.max() - minY + pad
        val defsSvg = if (defs.markers.isEmpty() && defs.extra.isEmpty()) "" else
            "<defs class=\"la-notation-markers\">${defs.markers.values.joinToString("")}${defs.extra.values.joinToString("")}</defs>\n"
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" class=\"la-diagram\"" +
            " width=\"${fmt(w)}\" height=\"${fmt(h)}\" viewBox=\"${fmt(minX)} ${fmt(minY)} ${fmt(w)} ${fmt(h)}\"" +
            (context?.let { " data-context=\"${xml(it)}\"" } ?: "") + ">\n" +
            defsSvg + "<g class=\"la-shapes\">\n$shapes</g>\n<g class=\"la-lines\">\n$lines</g>\n</svg>"
        return RenderedDiagram(svg, drawn, w, h)
    }

    private fun idAttr(fragment: String?, withIds: Boolean) = when {
        fragment == null -> ""
        withIds -> " id=\"${xml(fragment)}\" data-frag=\"${xml(fragment)}\""
        else -> " data-frag=\"${xml(fragment)}\""
    }

    private fun link(href: String?, body: String) = if (href == null) body else "<a href=\"${xml(href)}\" xlink:href=\"${xml(href)}\">$body</a>"

    private fun font(look: Appearance?): Shapes.TextFont = Shapes.TextFont(fontFamilyOf(look?.fontName), look?.fontSize ?: Defaults.FONT_SIZE, look?.fontStyle)

    private fun fontFamilyOf(name: String?): String {
        val family = name?.replace(Regex("[\"\\\\;{}<>]"), "")?.trim()
        return if (!family.isNullOrEmpty()) "\"$family\", ${Defaults.FONT_FAMILY}" else Defaults.FONT_FAMILY
    }

    private fun fontAttrs(f: Shapes.TextFont) = buildString {
        append(" font-size=\"${fmt(f.size)}\" font-family=\"${xml(f.family)}\"")
        if (Appearance.hasFontStyle(f.style, "bold")) append(" font-weight=\"bold\"")
        if (Appearance.hasFontStyle(f.style, "italic")) append(" font-style=\"italic\"")
        if (Appearance.hasFontStyle(f.style, "underline")) append(" text-decoration=\"underline\"")
    }

    private fun gradientFill(defs: Defs, fill: String, side: String?): String {
        if (side == null || fill == "none" || fill.startsWith("url(")) return fill
        val id = "${defs.prefix}la-gradient-$side-${fill.lowercase().replace(Regex("[^a-z0-9]"), "")}"
        val v = mapOf("top" to "0 0 0 1", "bottom" to "0 1 0 0", "left" to "0 0 1 0", "right" to "1 0 0 0")[side] ?: return fill
        val (x1, y1, x2, y2) = v.split(" ")
        defs.extra[id] = "<linearGradient id=\"$id\" x1=\"$x1\" y1=\"$y1\" x2=\"$x2\" y2=\"$y2\"><stop offset=\"0\" stop-color=\"${xml(fill)}\"/><stop offset=\"1\" stop-color=\"#ffffff\"/></linearGradient>"
        return "url(#$id)"
    }

    private fun colourFilter(defs: Defs, colour: String): String {
        val id = "${defs.prefix}la-icon-colour-${colour.lowercase().replace(Regex("[^a-z0-9]"), "")}"
        defs.extra[id] = "<filter id=\"$id\" x=\"0\" y=\"0\" width=\"1\" height=\"1\" color-interpolation-filters=\"sRGB\"><feFlood flood-color=\"${xml(colour)}\" result=\"colour\"/><feComposite in=\"colour\" in2=\"SourceAlpha\" operator=\"in\"/></filter>"
        return " filter=\"url(#$id)\""
    }

    private fun iconHref(value: String?, back: String): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val site = icons.href(v) ?: return if (v.startsWith("data:") || v.startsWith("https://") || v.startsWith("http://")) v else null
        return if (site.startsWith("data:") || site.startsWith("http")) site else back + site
    }

    private fun image(box: Box, href: String, cls: String, preserve: String = "xMidYMid meet", extra: String = "") =
        "<image class=\"$cls\" x=\"${fmt(box.x)}\" y=\"${fmt(box.y)}\" width=\"${fmt(box.width)}\" height=\"${fmt(box.height)}\" href=\"${xml(href)}\" xlink:href=\"${xml(href)}\" preserveAspectRatio=\"$preserve\"$extra/>"

    /** A picture in a node (`arch-vis:image`), over its fill and under its text and glyph, fitted in [area] at its position. */
    private fun pictureSvg(look: Appearance, area: Box): String {
        val href = Appearance.imageUri(look.image) ?: return ""
        if (area.width <= 0 || area.height <= 0) return ""
        val position = look.imagePosition ?: "TopLeft"
        val align = if (position == "Fill") "none" else {
            val x = when { position.endsWith("Left") -> "xMin"; position.endsWith("Right") -> "xMax"; else -> "xMid" }
            val y = when { position.startsWith("Top") -> "YMin"; position.startsWith("Bottom") -> "YMax"; else -> "YMid" }
            "$x$y meet"
        }
        return image(area, href, "la-shape-image", align, " pointer-events=\"none\"")
    }

    private fun labelSvg(label: String, area: Box, color: String, f: Shapes.TextFont, badgePlacement: String? = null, below: Boolean = false,
                         outside: Boolean = false, maxLines: Int? = null, align: String? = null, position: String? = null): String {
        val local = Shapes.labelLayout(label, area.width, area.height, badgePlacement, f.size, below, outside, maxLines, align, position, f)
        if (local.lines.isEmpty()) return ""
        val x = round2(area.x + local.x); val y = round2(area.y + local.y)
        val spans = local.lines.withIndex().joinToString("") { (i, line) ->
            "<tspan x=\"${fmt(x)}\" dy=\"${if (i == 0) "0" else fmt(local.lineHeight)}\">${xml(line)}</tspan>"
        }
        return "<text class=\"la-shape-label\" x=\"${fmt(x)}\" y=\"${fmt(y)}\" text-anchor=\"${local.anchor}\"${fontAttrs(f)} fill=\"${xml(color)}\">$spans</text>"
    }

    private fun containerLayout(layout: ShapeLayout, hasChildShapes: Boolean): ShapeLayout {
        if (!hasChildShapes || layout.label != layout.content) return layout
        val band = min(layout.content.height, Shapes.containerNameBand())
        return layout.copy(label = layout.content.copy(height = band), maxLabelLines = 2, labelBesideBadge = true)
    }

    private fun drawElement(n: Node, context: String?, styles: NotationStyles, defs: Defs, hasChildShapes: Boolean, back: String,
                            hrefOf: (String) -> String?, withIds: Boolean): Pair<String, List<Pair<Double, Double>>> {
        val element = n.elementIri!!
        val typeIri = types.typeOf(element, styles)
        val notation = styles.styleFor(typeIri)
        val ownLook = (n.own ?: Appearance.NONE).overlay(context?.let { n.contextLooks[it] }).takeUnless { it.isEmpty() }
        // Its own fill, line colour, outline and picture over its notation's; drawn as another of its notation's figures when its style says so.
        var style: VisualStyle? = if (ownLook != null) (notation ?: VisualStyle()).copy(
            fillColor = ownLook.fillColor ?: notation?.fillColor,
            lineColor = ownLook.lineColor ?: notation?.lineColor,
            borderStyle = ownLook.borderStyle ?: notation?.borderStyle,
            iconSymbol = ownLook.iconSymbol ?: notation?.iconSymbol,
        ) else notation
        ownLook?.shapeType?.let { shapeIri -> styles.shapeFor(typeIri, shapeIri)?.let { (st, cs) -> style = (style ?: VisualStyle()).copy(shapeType = st ?: style?.shapeType, customShape = cs) } }
        val notationLook = notation?.let { Appearance(fillColor = it.fillColor, lineColor = it.lineColor, fontColor = it.fontColor, borderStyle = it.borderStyle, renderingMode = it.renderingMode) .overlay(it.look) } ?: Appearance.NONE
        val look = notationLook.overlay(ownLook)
        val f = font(look)
        val w = n.bounds.width; val h = n.bounds.height
        val name = labels.displayName(element, context)
        val expression = Notation.labelExpressionIn(n.labelExpressions, context) ?: styles.labelExpressionFor(typeIri)
        val label = expression?.let { labels.evaluate(it, element, typeIri, context) }?.takeIf { it.isNotEmpty() }
            ?: name ?: typeIri?.let { pack.typeLabel(it) } ?: localName(element)

        // Its icon: its own, else its element's picture where it is drawn as its icon, else its notation's glyph.
        val picture = labels.pictureOf(element, context)?.let { iconHref(it, back) }
        var elementPicture = false
        var href: String? = null
        if (ownLook?.iconSymbol == null && picture != null) {
            val declared = ownLook?.renderingMode ?: styles.renderingModeFor(typeIri)
            if (ownLook?.useElementPicture == true || declared == "IconCentric") { href = picture; elementPicture = true }
        }
        if (href == null) href = iconHref(style?.iconSymbol, back)
        val declared = ownLook?.renderingMode ?: styles.renderingModeFor(typeIri)
        val mode = if (declared == "ShapeOnly" || href == null) "ShapeOnly" else declared
        val iconColor = if (ownLook?.iconSymbol != null || elementPicture) null else look.iconColor

        val typeName = typeIri?.let { pack.typeLabel(it) ?: localName(it) } ?: ""
        val body = StringBuilder()
        body.append("<title>${xml(name ?: label)}${if (typeName.isNotEmpty()) " — ${xml(typeName)}" else ""}</title>")
        val outline: List<Pair<Double, Double>>
        if (mode == "IconCentric") {
            body.append(pictureSvg(look, Box(0.0, 0.0, w, h)))
            body.append("<rect class=\"la-icon-container\" x=\"0\" y=\"0\" width=\"${fmt(w)}\" height=\"${fmt(h)}\" fill=\"none\" stroke=\"none\" pointer-events=\"all\"/>")
            body.append(image(Box(0.0, 0.0, w, max(1.0, h - Defaults.ICON_CENTRIC_LABEL_BAND)), href!!, "la-shape-icon", extra = iconColor?.let { colourFilter(defs, it) } ?: ""))
            body.append(labelSvg(label, Box(0.0, 0.0, w, h), look.fontColor ?: Defaults.LINE, f, below = true))
            outline = Shapes.outlinePolygon("Rectangle", null, n.bounds.x, n.bounds.y, w, h)
        } else {
            val geometry = Shapes.shapeGeometry(style?.shapeType, style?.customShape, w, h)
            val lineColor = style?.lineColor ?: Defaults.LINE
            val dash = Shapes.strokeDashArray(style?.borderStyle)
            val stroke = " stroke=\"${xml(lineColor)}\" stroke-width=\"${fmt(look.lineWidth ?: Defaults.STROKE_WIDTH)}\"" + (dash?.let { " stroke-dasharray=\"$it\"" } ?: "")
            val fill = gradientFill(defs, style?.fillColor ?: Defaults.FILL, look.gradient)
            body.append("<${geometry.tag} class=\"la-shape-body\"").append(geometry.attrs.joinToString("") { (k, v) -> " $k=\"${xml(v)}\"" })
                .append(" fill=\"${xml(fill)}\"$stroke/>")
            geometry.details?.let { body.append("<path class=\"la-shape-details\" d=\"${xml(it)}\" fill=\"none\"$stroke/>") }
            outline = Shapes.outlinePolygon(style?.shapeType, style?.customShape, n.bounds.x, n.bounds.y, w, h)
            val color = look.fontColor ?: notation?.lineColor ?: Defaults.LINE
            if (!Shapes.labelFitsInside(w, h)) {
                // A junction: no room for a badge, and its name, if it has one, under it.
                if (name != null) body.append(labelSvg(label, Box(0.0, 0.0, w, h), color, f, outside = true))
            } else {
                val inside = if (look.image != null || ownLook?.iconSymbol != null || ownLook?.useElementPicture == true || picture == null) look
                    else Appearance(image = labels.pictureOf(element, context), imagePosition = look.imagePosition)
                body.append(pictureSvg(inside, Box(3.0, 3.0, max(0.0, w - 6), max(0.0, h - 6))))
                val layout = containerLayout(Shapes.shapeLayout(style?.shapeType, style?.customShape, w, h), hasChildShapes)
                val badgePlacement = if (mode == "ShapeWithBadge" && href != null) (style?.iconPlacement ?: "TopRight") else null
                if (badgePlacement != null) {
                    val b = Shapes.badgeBox(badgePlacement, layout.content.width, layout.content.height)
                    body.append(image(Box(round2(layout.content.x + b.x), round2(layout.content.y + b.y), b.size, b.size), href!!, "la-shape-badge",
                        extra = " data-icon-placement=\"$badgePlacement\"" + (iconColor?.let { colourFilter(defs, it) } ?: "")))
                }
                body.append(labelSvg(label, layout.label, color, f,
                    badgePlacement = if (layout.label == layout.content || layout.labelBesideBadge) badgePlacement else null,
                    maxLines = layout.maxLabelLines, align = look.textAlignment, position = look.textPosition))
            }
        }
        val attrs = " class=\"la-shape la-mode-$mode node\"${idAttr(n.fragment, withIds)} data-element-iri=\"${xml(element)}\"" +
            (typeIri?.let { " data-type-iri=\"${xml(it)}\"" } ?: "") + " transform=\"translate(${fmt(n.bounds.x)} ${fmt(n.bounds.y)})\""
        return "<g$attrs>${link(hrefOf(element), body.toString())}</g>" to outline
    }

    private val figureDefaults = mapOf(
        "group" to Triple("#f1f3f4", "#8a9399", "#202124"),
        "shape" to Triple("#ffffff", "#5f6368", "#202124"),
        "note" to Triple("#fff7b3", "#d4b106", "#202124"),
        "reference" to Triple("#dcebeb", "#5c5c5c", "#202124"),
    )

    /** A figure: drawing only, no model element behind it — a group, a note, a shape, or a reference to another view. */
    private fun drawFigure(n: Node, context: String?, defs: Defs, hasChildShapes: Boolean, back: String, hrefOf: (String) -> String?,
                           withIds: Boolean): Pair<String, List<Pair<Double, Double>>> {
        val kind = n.figure!!
        val look = (n.own ?: Appearance.NONE).overlay(context?.let { n.contextLooks[it] })
        val (dFill, dLine, dFont) = figureDefaults.getValue(kind)
        val fill = look.fillColor ?: dFill; val line = look.lineColor ?: dLine; val fontColor = look.fontColor ?: dFont
        val f = font(look)
        val w = n.bounds.width; val h = n.bounds.height
        val text = n.figureText ?: n.referencedView?.let { labels.displayName(it, context) } ?: ""
        val dash = Shapes.strokeDashArray(look.borderStyle)
        val outlineAttrs = " stroke=\"${xml(line)}\" stroke-width=\"${fmt(look.lineWidth ?: Defaults.STROKE_WIDTH)}\"" + (dash?.let { " stroke-dasharray=\"$it\"" } ?: "")
        val body = StringBuilder()
        if (text.isNotEmpty()) body.append("<title>${xml(text)}</title>")
        var outline = Shapes.outlinePolygon("Rectangle", null, n.bounds.x, n.bounds.y, w, h)
        fun figureText(area: Box, maxLines: Int, align: String, position: String): String {
            val lines = if (text.isEmpty()) emptyList() else Shapes.wrapLabel(text, max(0.0, area.width), f.size, maxLines, f)
            if (lines.isEmpty()) return ""
            val lineHeight = (f.size * 1.2).roundToInt().toDouble()
            val block = lines.size * lineHeight
            val top = when (position) { "middle" -> area.y + (area.height - block) / 2; "bottom" -> area.y + area.height - block; else -> area.y }
            val y = if (lines.size == 1 && maxLines == 1) area.y + (area.height + f.size * 0.7) / 2 else top + f.size
            val x = when (align) { "center" -> area.x + area.width / 2; "right" -> area.x + area.width; else -> area.x }
            val anchor = when (align) { "center" -> " text-anchor=\"middle\""; "right" -> " text-anchor=\"end\""; else -> "" }
            val spans = lines.withIndex().joinToString("") { (i, l) -> "<tspan x=\"${fmt(x)}\" dy=\"${if (i == 0) "0" else fmt(lineHeight)}\">${xml(l)}</tspan>" }
            return "<text class=\"la-shape-label\" x=\"${fmt(x)}\" y=\"${fmt(y)}\"$anchor${fontAttrs(f)} fill=\"${xml(fontColor)}\">$spans</text>"
        }
        when (kind) {
            "group" -> {
                val tabWidth = min(w, max(60.0, Shapes.textWidth(text) + 16))
                val tabHeight = min(20.0, h)
                body.append("<rect class=\"la-shape-details\" x=\"0\" y=\"0\" width=\"${fmt(tabWidth)}\" height=\"${fmt(tabHeight)}\" fill=\"${xml(look.fillColor ?: "#e3e7ea")}\"$outlineAttrs/>")
                body.append("<rect class=\"la-shape-body\" x=\"0\" y=\"${fmt(tabHeight)}\" width=\"${fmt(w)}\" height=\"${fmt(max(0.0, h - tabHeight))}\" fill=\"${xml(gradientFill(defs, fill, look.gradient))}\"$outlineAttrs/>")
                body.append(pictureSvg(look, Box(3.0, 3.0 + tabHeight, max(0.0, w - 6), max(0.0, h - tabHeight - 6))))
                body.append(figureText(Box(6.0, 0.0, tabWidth - 8, tabHeight), 1, "left", "top"))
            }
            "note" -> {
                val fold = minOf(14.0, w / 4, h / 4)
                body.append("<path class=\"la-shape-body\" d=\"M0,0 H${fmt(w - fold)} L${fmt(w)},${fmt(fold)} V${fmt(h)} H0 Z\" fill=\"${xml(gradientFill(defs, fill, look.gradient))}\"$outlineAttrs/>")
                body.append("<path class=\"la-shape-details\" d=\"M${fmt(w - fold)},0 V${fmt(fold)} H${fmt(w)} Z\" fill=\"${xml(look.fillColor ?: "#f2e58a")}\"$outlineAttrs/>")
                if (look.fillColor != null) body.append("<path d=\"M${fmt(w - fold)},0 V${fmt(fold)} H${fmt(w)} Z\" fill=\"#000000\" fill-opacity=\"0.12\" stroke=\"none\"/>")
                body.append(pictureSvg(look, Box(3.0, 3.0, max(0.0, w - 6), max(0.0, h - 6))))
                val lineHeight = (f.size * 1.2).roundToInt()
                body.append(figureText(Box(6.0, 4.0, w - 12 - fold / 2, h - 8), max(1, ((h - 8) / lineHeight).toInt()), look.textAlignment ?: "left", look.textPosition ?: "top"))
            }
            else -> {
                val shapeType = n.figureShape?.let { localName(it) }?.takeIf { it in SHAPE_TYPES } ?: "Rectangle"
                val g = Shapes.shapeGeometry(shapeType, null, w, h)
                body.append("<${g.tag} class=\"la-shape-body\"").append(g.attrs.joinToString("") { (k, v) -> " $k=\"${xml(v)}\"" })
                    .append(" fill=\"${xml(gradientFill(defs, fill, look.gradient))}\"$outlineAttrs/>")
                body.append(pictureSvg(look, Box(3.0, 3.0, max(0.0, w - 6), max(0.0, h - 6))))
                if (kind == "reference") body.append("<g class=\"la-shape-details\" transform=\"translate(5,5)\"><rect x=\"0\" y=\"0\" width=\"12\" height=\"9\" fill=\"none\" stroke=\"${xml(line)}\" stroke-width=\"1\"/><line x1=\"0\" y1=\"3\" x2=\"12\" y2=\"3\" stroke=\"${xml(line)}\" stroke-width=\"1\"/></g>")
                val layout = containerLayout(Shapes.shapeLayout(shapeType, null, w, h), hasChildShapes)
                body.append(labelSvg(text, layout.label, fontColor, f, maxLines = layout.maxLabelLines, align = look.textAlignment, position = look.textPosition))
                outline = Shapes.outlinePolygon(shapeType, null, n.bounds.x, n.bounds.y, w, h)
            }
        }
        val target = n.referencedView?.let(hrefOf)
        val attrs = " class=\"la-shape la-figure la-figure-$kind node\"${idAttr(n.fragment, withIds)} data-figure=\"$kind\" transform=\"translate(${fmt(n.bounds.x)} ${fmt(n.bounds.y)})\""
        return "<g$attrs>${link(target, body.toString())}</g>" to outline
    }

    private fun drawLink(l: Link, waypoints: List<Point>, context: String?, styles: NotationStyles, defs: Defs, back: String,
                         hrefOf: (String) -> String?, withIds: Boolean, relationshipNames: Boolean): String {
        val own = (l.own ?: Appearance.NONE).overlay(context?.let { l.contextLooks[it] }).takeUnless { it.isEmpty() }
        val connector = l.relationshipIri == null
        val relType = l.relationshipIri?.let { types.typeOf(it, styles) }
        val style = if (connector) null else styles.relationshipStyleFor(relType)
        val look = (style?.look() ?: Appearance.NONE).overlay(own)
        val lineColor = look.lineColor ?: if (connector) "#5f6368" else Defaults.LINE
        val lineStyle = if (connector) own?.lineStyle ?: "DashedLine" else style?.lineStyle
        val dash = if (connector && lineStyle == "DashedLine") "4 3" else Shapes.strokeDashArray(lineStyle)
        val sourceDecor = if (connector) own?.sourceDecor else style?.sourceDecor
        val targetDecor = if (connector) own?.targetDecor else style?.targetDecor
        val markers = StringBuilder()
        for ((end, decoration) in listOf("source" to sourceDecor, "target" to targetDecor)) {
            if (decoration == null || decoration == "NoDecoration") continue
            val id = defs.prefix + Shapes.markerId(decoration, end, lineColor)
            if (id !in defs.markers) {
                val symbol = styles.decorationSymbolFor(decoration)?.let { symbolOf(it) }
                Shapes.markerSvg(decoration, end, lineColor, symbol)?.let { defs.markers[id] = it.replaceFirst("id=\"", "id=\"${defs.prefix}") }
            }
            if (id in defs.markers) markers.append(" marker-${if (end == "source") "start" else "end"}=\"url(#$id)\"")
        }
        val pts = waypoints.joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" }
        val typeName = relType?.let { pack.typeLabel(it) ?: localName(it) }
        val name = l.relationshipIri?.let { labels.displayName(it, context) } ?: l.text
        val title = listOfNotNull(name, typeName).joinToString(" — ")
        val body = StringBuilder()
        if (title.isNotEmpty()) body.append("<title>${xml(title)}</title>")
        // A wide, invisible line under the drawn one, so that a thin line is easy to point at and click.
        body.append("<polyline class=\"la-hit\" points=\"$pts\" fill=\"none\" stroke=\"transparent\" stroke-width=\"10\"/>")
        body.append("<polyline class=\"la-connection${if (connector) " la-connector" else ""}\" points=\"$pts\" fill=\"none\" stroke=\"${xml(lineColor)}\"" +
            " stroke-width=\"${fmt(look.lineWidth ?: Defaults.STROKE_WIDTH)}\" stroke-linejoin=\"round\"" + (dash?.let { " stroke-dasharray=\"$it\"" } ?: "") +
            " data-line-style=\"${lineStyle ?: "SolidLine"}\"$markers/>")
        val points = waypoints.drop(1).dropLast(1).joinToString("") { p ->
            if (p.fragment == null) "" else "<circle class=\"point\"${idAttr(p.fragment, withIds)} cx=\"${fmt(p.x)}\" cy=\"${fmt(p.y)}\" r=\"4\" fill=\"none\"/>"
        }
        body.append(points)
        // Its name on its line, on a white box, as la-gram draws it when names are shown: the page's switch shows them.
        val text = if (connector) l.text else if (relationshipNames) name else null
        if (!text.isNullOrBlank()) body.append(connectionLabel(waypoints, text, look, l, connector))
        val attrs = " class=\"link${if (connector) " connector" else ""}\"${idAttr(l.fragment, withIds)}" +
            (l.relationshipIri?.let { " data-relationship-iri=\"${xml(it)}\"" } ?: "") + (relType?.let { " data-type-iri=\"${xml(it)}\"" } ?: "")
        return "<g$attrs>${link(l.relationshipIri?.let(hrefOf), body.toString())}</g>"
    }

    private fun connectionLabel(waypoints: List<Point>, text: String, look: Appearance, l: Link, connector: Boolean): String {
        val middle = midpoint(waypoints)
        val fontSize = look.fontSize ?: Defaults.FONT_SIZE
        val lineHeight = (fontSize * 1.2).roundToInt().toDouble()
        val lines = Shapes.wrapLabel(text, Defaults.WIDTH, fontSize)
        if (lines.isEmpty()) return ""
        val width = (lines.maxOf { Shapes.textWidth(it, fontSize) }) + 4
        val height = lines.size * lineHeight
        val at = l.labelBox?.let { Point(it.x + it.width / 2, it.y + it.height / 2) } ?: when (look.labelAnchor) {
            "source", "target" -> anchoredAt(waypoints, look.labelAnchor, width, height)
            else -> middle
        }
        val top = at.y - height / 2
        val color = look.fontColor ?: look.lineColor ?: Defaults.LINE
        val f = font(look.copy(fontSize = fontSize))
        val spans = lines.withIndex().joinToString("") { (i, line) -> "<tspan x=\"${fmt(at.x)}\" dy=\"${if (i == 0) "0" else fmt(lineHeight)}\">${xml(line)}</tspan>" }
        return "<g class=\"la-connection-label${if (connector) " la-connector-label" else ""}\"><rect x=\"${fmt(at.x - width / 2)}\" y=\"${fmt(top)}\" width=\"${fmt(width)}\" height=\"${fmt(height)}\" fill=\"#ffffff\" stroke=\"none\"/>" +
            "<text x=\"${fmt(at.x)}\" y=\"${fmt(top + fontSize * 0.85)}\" text-anchor=\"middle\"${fontAttrs(f)} fill=\"${xml(color)}\" stroke=\"#ffffff\" stroke-width=\"3\" stroke-linejoin=\"round\" paint-order=\"stroke\">$spans</text></g>"
    }

    private fun midpoint(points: List<Point>): Point {
        if (points.size < 2) return points.firstOrNull() ?: Point(0.0, 0.0)
        val total = points.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        return pointAlong(points, total / 2)
    }

    private fun pointAlong(points: List<Point>, distance: Double): Point {
        var remaining = distance
        for ((a, b) in points.zipWithNext()) {
            val length = hypot(b.x - a.x, b.y - a.y)
            if (remaining <= length) {
                val f = if (length == 0.0) 0.0 else remaining / length
                return Point(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
            remaining -= length
        }
        return points.last()
    }

    private fun anchoredAt(points: List<Point>, end: String, width: Double, height: Double): Point {
        val route = if (end == "source") points else points.reversed()
        val (a, b) = route[0] to route[1]
        val length = hypot(b.x - a.x, b.y - a.y)
        val reach = if (length == 0.0) width / 2 else (Math.abs(b.x - a.x) * width + Math.abs(b.y - a.y) * height) / (2 * length)
        val total = route.zipWithNext().sumOf { (p, q) -> hypot(q.x - p.x, q.y - p.y) }
        return pointAlong(route, min(total / 2, reach + 8))
    }

    /**
     * Where a route meets an outline, as diagram-js's cropping chooses: the start where the route last leaves its
     * source, the end where it first enters its target, each rounded to a whole pixel; null when it never crosses.
     */
    private fun dock(outline: List<Pair<Double, Double>>, path: List<Point>, cropStart: Boolean): Point? {
        data class Hit(val x: Double, val y: Double, val along: Double)
        val hits = mutableListOf<Hit>()
        for ((i, seg) in path.zipWithNext().withIndex()) {
            val (a, b) = seg
            for (k in outline.indices) {
                val (cx, cy) = outline[k]
                val (dx, dy) = outline[(k + 1) % outline.size]
                val rX = b.x - a.x; val rY = b.y - a.y; val sX = dx - cx; val sY = dy - cy
                val denom = rX * sY - rY * sX
                if (Math.abs(denom) < 1e-12) continue
                val tt = ((cx - a.x) * sY - (cy - a.y) * sX) / denom
                val u = ((cx - a.x) * rY - (cy - a.y) * rX) / denom
                if (tt in 0.0..1.0 && u in 0.0..1.0) hits.add(Hit(a.x + tt * rX, a.y + tt * rY, i + tt))
            }
        }
        if (hits.isEmpty()) return null
        val chosen = if (cropStart) hits.maxBy { it.along } else hits.minBy { it.along }
        return Point(chosen.x.roundToInt().toDouble(), chosen.y.roundToInt().toDouble())
    }

    private val symbols = ConcurrentHashMap<String, Optional>()

    private class Optional(val symbol: Shapes.DecorationSymbol?)

    /** A decoration's symbol, as meta.linked.archi publishes it; null, and the built-in geometry, when it cannot be read. */
    private fun symbolOf(iri: String): Shapes.DecorationSymbol? = symbols.computeIfAbsent(iri) {
        Optional(Published.text(iri)?.let { Shapes.parseDecorationSymbol(it) })
    }.symbol
}

/**
 * Which of a concept's types it is drawn and listed as: the one its notation describes, among its specific types —
 * not the core's generic classes — else one a loaded language knows, else its first. A concept is often typed
 * `arch:Element` and `am4:BusinessActor` both, and is drawn as a Business Actor.
 */
class TypeChooser(private val t: Triples, private val pack: NotationPack) {
    private val cache = ConcurrentHashMap<String, String>()
    private val none = "\u0000"

    fun candidates(iri: String): List<String> {
        val all = t.types(iri(iri)).filter { !it.startsWith("http://www.w3.org/") && !it.startsWith(NS.VIS) }.distinct()
        val (core, specific) = all.partition { it.startsWith(NS.ARCH) }
        return specific.sorted() + core.sorted()
    }

    fun typeOf(iri: String, styles: NotationStyles): String? {
        val chosen = cache.getOrPut(iri) {
            val types = candidates(iri)
            types.firstOrNull { styles.describes(it) } ?: types.firstOrNull { pack.knows(it) } ?: types.firstOrNull() ?: none
        }
        return chosen.takeIf { it != none }
    }
}
