package archi.linked.rdf2docs.notation

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Value

/*
 * The `arch-vis:` visual-notation layer read out of the notation pack, ported from the kernel's
 * `metamodel-manager/notation.ts`, which la-gram draws with:
 *
 *   PresentationContext --arch:usesNotationSet--> NotationSet
 *   NotationSet <--arch-vis:inNotationSet-- VisualNotation
 *   VisualNotation --arch-vis:notationFor--> element or relationship class
 *   VisualNotation --arch-vis:defaultStyle--> Style
 *   VisualNotation --arch:prefVisNotation--> the published picture of the class
 *
 * Read the same way so that a figure looks as la-gram draws it: the same fallbacks, the same defaults.
 */

/** The core shapes (core-vis), which every renderer draws natively. */
val SHAPE_TYPES = listOf("Rectangle", "RoundedRectangle", "Octagon", "Ellipse", "Circle", "Hexagon", "Diamond", "Parallelogram")
val ICON_PLACEMENTS = listOf("TopRight", "TopLeft", "TopCenter", "Center", "BottomCenter")
val RENDERING_MODES = listOf("ShapeWithBadge", "IconCentric", "ShapeOnly")
val LINE_STYLES = listOf("SolidLine", "DashedLine", "DottedLine")
val DECORATIONS = listOf("NoDecoration", "OpenArrowhead", "ClosedArrowhead", "HollowArrowhead", "HollowDiamond", "FilledDiamond", "HalfArrowhead", "CircleDot")

/**
 * A shape a notation declares itself (an `arch-vis:ShapeType` outside core-vis): the core shape to fall back to and
 * its geometry as path templates — SVG path data whose numbers may be `{expressions}` over `w`, `h` and the variables.
 */
data class CustomShape(
    val iri: String,
    val label: String? = null,
    val fallback: String? = null,
    val variables: String? = null,
    val outline: String,
    val details: String? = null,
    val contentBox: String? = null,
    val labelBox: String? = null,
)

/** A label expression as a notation, a style or a presentation context gives it: its text, and the context it is for. */
data class LabelExpression(val expression: String, val context: String? = null)

/** How an element type is drawn: the construction recipe. */
data class VisualStyle(
    val shapeType: String? = null,
    val customShape: CustomShape? = null,
    val fillColor: String? = null,
    val lineColor: String? = null,
    val fontColor: String? = null,
    val iconSymbol: String? = null,
    val iconPlacement: String? = null,
    val renderingMode: String? = null,
    val defaultWidth: Double? = null,
    val defaultHeight: Double? = null,
    val borderStyle: String? = null,
    /** What the style says of the look a placement's own style may set too (core-vis 0.7). */
    val look: Appearance = Appearance.NONE,
    val labelExpressions: List<LabelExpression> = emptyList(),
    val alternatives: List<VisualStyle> = emptyList(),
)

/** How a relationship type is drawn. */
data class RelationshipStyle(
    val lineStyle: String? = null,
    val sourceDecor: String? = null,
    val targetDecor: String? = null,
    val lineColor: String? = null,
    val fontColor: String? = null,
    val labelAnchor: String? = null,
    val nestedEnds: List<String> = emptyList(),
    val lineWidth: Double? = null,
    val fontName: String? = null,
    val fontSize: Double? = null,
    val fontStyle: String? = null,
) {
    fun look() = Appearance(lineColor = lineColor, fontColor = fontColor, lineWidth = lineWidth, fontName = fontName, fontSize = fontSize, fontStyle = fontStyle, labelAnchor = labelAnchor)
}

/** The notation of one concept (element or relationship class). */
data class VisualNotation(
    val conceptIri: String,
    val notationSetIri: String,
    val prefVisNotation: String? = null,
    val style: VisualStyle? = null,
    val relationshipStyle: RelationshipStyle? = null,
)

data class NotationSet(val iri: String, val label: String, val notations: Map<String, VisualNotation>)

object Notation {

    private fun coreShape(iri: String?): String? {
        if (iri == null || !iri.startsWith(NS.VIS)) return null
        return iri.removePrefix(NS.VIS).takeIf { it in SHAPE_TYPES }
    }

    private fun fragment(iri: String?): String? {
        if (iri == null) return null
        return localName(iri).takeIf { it.isNotEmpty() }
    }

    private fun enumOf(value: String?, allowed: List<String>): String? = fragment(value)?.takeIf { it in allowed }

    private fun number(value: String?): Double? = value?.trim()?.toDoubleOrNull()

    /** A notation's own shape as the pack describes it; null for a core shape, or one with no outline to draw. */
    fun readCustomShape(t: Triples, shapeIri: String): CustomShape? {
        if (coreShape(shapeIri) != null) return null
        val s = iri(shapeIri)
        val outline = t.firstString(s, NS.VIS + "outlinePath") ?: return null
        return CustomShape(
            iri = shapeIri,
            label = if (t.objects(s, NS.SKOS + "prefLabel").isNotEmpty()) t.labelOf(shapeIri) else null,
            fallback = coreShape(t.firstString(s, NS.VIS + "fallbackShape")),
            variables = t.firstString(s, NS.VIS + "shapeVariables"),
            outline = outline,
            details = t.firstString(s, NS.VIS + "detailPath"),
            contentBox = t.firstString(s, NS.VIS + "contentBox"),
            labelBox = t.firstString(s, NS.VIS + "labelBox"),
        )
    }

    private fun readShape(t: Triples, style: Resource): Pair<String?, CustomShape?> {
        val term = t.objects(style, NS.VIS + "shapeType").filterIsInstance<IRI>().firstOrNull() ?: return null to null
        coreShape(term.stringValue())?.let { return it to null }
        val custom = readCustomShape(t, term.stringValue())
        val fallback = custom?.fallback ?: coreShape(t.firstString(term, NS.VIS + "fallbackShape"))
        return fallback to custom
    }

    private fun extractElementStyle(t: Triples, style: Resource): VisualStyle? {
        val (shapeType, customShape) = readShape(t, style)
        fun s(key: String) = t.firstString(style, NS.VIS + key)
        val iconSymbol = s("iconSymbol")
        val declaredPlacement = enumOf(s("iconPlacement"), ICON_PLACEMENTS)
        val look = Appearance.read(t, style).let {
            // Only the keys core-vis 0.7 lets a notation say of the look.
            Appearance(lineWidth = it.lineWidth, fontName = it.fontName, fontSize = it.fontSize, fontStyle = it.fontStyle,
                textAlignment = it.textAlignment, textPosition = it.textPosition, gradient = it.gradient, iconColor = it.iconColor)
        }
        val result = VisualStyle(
            shapeType = shapeType,
            customShape = customShape,
            fillColor = s("fillColor"),
            lineColor = s("lineColor"),
            fontColor = s("fontColor"),
            iconSymbol = iconSymbol,
            // Fallback rule: an icon without a placement is at the top right.
            iconPlacement = declaredPlacement ?: if (iconSymbol != null) "TopRight" else null,
            renderingMode = enumOf(s("renderingMode"), RENDERING_MODES),
            defaultWidth = number(s("defaultWidth")),
            defaultHeight = number(s("defaultHeight")),
            borderStyle = enumOf(s("borderStyle"), LINE_STYLES),
            look = look,
        )
        val hasAny = shapeType != null || customShape != null || result.fillColor != null || result.lineColor != null ||
            iconSymbol != null || result.renderingMode != null || result.defaultWidth != null || result.defaultHeight != null ||
            result.borderStyle != null || result.fontColor != null || !look.isEmpty()
        return if (hasAny) result else null
    }

    private fun extractRelationshipStyle(t: Triples, subject: Resource): RelationshipStyle? {
        fun s(key: String) = t.firstString(subject, NS.VIS + key)
        val lineStyle = enumOf(s("lineStyle"), LINE_STYLES)
        val sourceDecor = enumOf(s("sourceDecor"), DECORATIONS)
        val targetDecor = enumOf(s("targetDecor"), DECORATIONS)
        val nestedEnds = t.objects(subject, NS.VIS + "nestedEnd").mapNotNull {
            when (it.stringValue()) { NS.VIS + "SourceEnd" -> "source"; NS.VIS + "TargetEnd" -> "target"; else -> null }
        }.sorted()
        val look = Appearance.read(t, subject)
        val anchor = when (s("labelAnchor")) {
            NS.VIS + "AnchorSource" -> "source"; NS.VIS + "AnchorMiddle" -> "middle"; NS.VIS + "AnchorTarget" -> "target"; else -> null
        }
        val fontColor = s("fontColor")
        if (lineStyle == null && sourceDecor == null && targetDecor == null && nestedEnds.isEmpty() && look.lineWidth == null &&
            look.fontName == null && look.fontSize == null && look.fontStyle == null && fontColor == null && anchor == null) return null
        return RelationshipStyle(lineStyle, sourceDecor, targetDecor, s("lineColor"), fontColor, anchor, nestedEnds,
            look.lineWidth, look.fontName, look.fontSize, look.fontStyle)
    }

    /** The label expressions [subject] gives (`arch-vis:labelExpression`), each with the presentation context it is for. */
    fun labelExpressionsOf(t: Triples, subject: Resource): List<LabelExpression> =
        t.objects(subject, NS.VIS + "labelExpression").filterIsInstance<Resource>().mapNotNull { label ->
            val expression = t.firstString(label, NS.VIS + "expression") ?: return@mapNotNull null
            LabelExpression(expression, t.firstString(label, NS.ARCH + "inPresentationContext"))
        }

    /** Of the expressions a place gives, the one for [context], else one for every context. */
    fun labelExpressionIn(expressions: List<LabelExpression>?, context: String?): String? {
        if (expressions.isNullOrEmpty()) return null
        return (if (context != null) expressions.firstOrNull { it.context == context }?.expression else null)
            ?: expressions.firstOrNull { it.context == null }?.expression
    }

    /**
     * The notation set with the given IRI. Relationship properties are read from the notation itself as well as from
     * its default style, because published notation files put them directly on the notation. Null when the set has no
     * notations.
     */
    fun buildVisualNotations(t: Triples, setIri: String): NotationSet? {
        val notations = linkedMapOf<String, VisualNotation>()
        for (notation in t.subjects(NS.VIS + "inNotationSet", iri(setIri))) {
            if (notation !is IRI) continue
            val concept = iriOrLiteral(t, notation, NS.VIS + "notationFor") ?: continue
            val prefVis = iriOrLiteral(t, notation, NS.ARCH + "prefVisNotation")
            var style: VisualStyle? = null
            var relStyle = extractRelationshipStyle(t, notation)
            val defaults = t.objects(notation, NS.VIS + "defaultStyle").filterIsInstance<Resource>()
            for (d in defaults) {
                style = extractElementStyle(t, d) ?: style
                relStyle = extractRelationshipStyle(t, d) ?: relStyle
            }
            val alternatives = t.objects(notation, NS.VIS + "alternativeStyle").filterIsInstance<Resource>().mapNotNull { extractElementStyle(t, it) }
            if (style != null && alternatives.isNotEmpty()) style = style.copy(alternatives = alternatives)
            val expressions = labelExpressionsOf(t, notation) + defaults.flatMap { labelExpressionsOf(t, it) }
            if (expressions.isNotEmpty()) style = (style ?: VisualStyle()).copy(labelExpressions = expressions)
            notations[concept] = VisualNotation(concept, setIri, prefVis, style, relStyle)
        }
        if (notations.isEmpty()) return null
        return NotationSet(setIri, t.labelOf(setIri), notations)
    }

    private fun iriOrLiteral(t: Triples, subject: Resource, predicate: String): String? {
        val objects = t.objects(subject, predicate)
        return (objects.firstOrNull { it is IRI } ?: objects.firstOrNull { it is Literal })?.stringValue()
    }

    /** The marker symbol published for each decoration: `arch-vis:decorSymbol` on core-vis's decoration individuals. */
    fun decorationSymbols(t: Triples): Map<String, String> {
        val symbols = linkedMapOf<String, String>()
        for (subject in t.subjectsOfType(NS.VIS + "Decoration")) {
            if (subject !is IRI || !subject.stringValue().startsWith(NS.VIS)) continue
            val decoration = subject.stringValue().removePrefix(NS.VIS).takeIf { it in DECORATIONS && it != "NoDecoration" } ?: continue
            t.firstString(subject, NS.VIS + "decorSymbol")?.let { symbols[decoration] = it }
        }
        return symbols
    }

    /** The set a presentation context asks for (`arch:usesNotationSet`), or null when it names none. */
    fun notationSetForContext(t: Triples, contextIri: String): String? =
        t.objects(iri(contextIri), NS.ARCH + "usesNotationSet").filterIsInstance<IRI>().firstOrNull()?.stringValue()

    /**
     * The rendering mode actually used, after the notation guide's fallbacks: absent is `ShapeWithBadge`; a badge
     * without an icon is `ShapeOnly`; `IconCentric` without an icon is `ShapeOnly`.
     */
    fun effectiveRenderingMode(style: VisualStyle?): String {
        val declared = style?.renderingMode ?: "ShapeWithBadge"
        val hasIcon = !style?.iconSymbol.isNullOrBlank()
        return when {
            declared == "ShapeOnly" -> "ShapeOnly"
            declared == "IconCentric" -> if (hasIcon) "IconCentric" else "ShapeOnly"
            else -> if (hasIcon) "ShapeWithBadge" else "ShapeOnly"
        }
    }

    /** Whether a value is a resource, for reading a style that may be a blank node. */
    internal fun isResource(value: Value?) = value is Resource
}
