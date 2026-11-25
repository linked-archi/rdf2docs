package archi.linked.rdf2docs.notation

import org.slf4j.LoggerFactory
import java.awt.Font
import java.awt.font.FontRenderContext
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * SVG geometry for the `arch-vis:` notation vocabulary, ported from la-gram's `ui/canvas/notation-shapes.ts`: the same
 * outlines, badge placement, label layout and end decorations, so a figure here is the figure la-gram draws. Pure:
 * it turns a shape type, an icon placement, a line style or a decoration into SVG text.
 */

data class Box(val x: Double, val y: Double, val width: Double, val height: Double)

/** An SVG element that draws a shape: its tag and attributes, and the inner lines drawn over its body. */
data class ShapeGeometry(val tag: String, val attrs: List<Pair<String, String>>, val details: String? = null)

data class ShapeLayout(val content: Box, val label: Box, val maxLabelLines: Int? = null, val labelBesideBadge: Boolean = false)

data class LabelLayout(val lines: List<String>, val x: Double, val y: Double, val lineHeight: Double, val anchor: String = "middle")

data class BadgeBox(val x: Double, val y: Double, val size: Double)

object Defaults {
    const val SHAPE_TYPE = "Rectangle"
    const val FILL = "#FFFFFF"
    const val LINE = "#000000"
    const val WIDTH = 120.0
    const val HEIGHT = 55.0
    const val STROKE_WIDTH = 1.0
    const val FONT_SIZE = 11.0
    const val FONT_FAMILY = "system-ui, -apple-system, \"Segoe UI\", sans-serif"
    const val BADGE_SIZE = 20.0
    const val BADGE_PADDING = 4.0
    const val CORNER_RADIUS = 10.0
    const val ICON_CENTRIC_LABEL_BAND = 16.0
    const val MAX_LABEL_LINES = 3
}

class ShapeTemplateError(message: String) : Exception(message)

object Shapes {
    private val log = LoggerFactory.getLogger(Shapes::class.java)

    /** How tall a container's name band is: two lines of its name and the badge padding. */
    fun containerNameBand(fontSize: Double = Defaults.FONT_SIZE, badgePadding: Double = Defaults.BADGE_PADDING): Double =
        2 * (fontSize * 1.2).roundToInt() + 2 * badgePadding

    private fun size(value: Double?, fallback: Double) = if (value != null && value.isFinite() && value > 0) value else fallback

    private fun points(pairs: List<Pair<Double, Double>>) = pairs.joinToString(" ") { (x, y) -> "${fmt(x)},${fmt(y)}" }

    /** Vertices of the polygonal core shapes, shape-local; null for any other shape. */
    private fun polygonVertices(type: String, w: Double, h: Double): List<Pair<Double, Double>>? = when (type) {
        "Octagon" -> {
            val c = minOf(min(w, h) * 0.25, w / 2, h / 2)
            listOf(c to 0.0, w - c to 0.0, w to c, w to h - c, w - c to h, c to h, 0.0 to h - c, 0.0 to c)
        }
        "Hexagon" -> {
            val c = min(w * 0.25, w / 2)
            listOf(c to 0.0, w - c to 0.0, w to h / 2, w - c to h, c to h, 0.0 to h / 2)
        }
        "Diamond" -> listOf(w / 2 to 0.0, w to h / 2, w / 2 to h, 0.0 to h / 2)
        "Parallelogram" -> {
            val skew = min(w * 0.2, w / 2)
            listOf(skew to 0.0, w to 0.0, w - skew to h, 0.0 to h)
        }
        else -> null
    }

    private fun cornerRadius(w: Double, h: Double) = minOf(Defaults.CORNER_RADIUS, w / 2, h / 2)

    /**
     * Geometry for a shape at [width] × [height]: a core shape natively, an unknown or absent one as a rectangle, a
     * notation's own from its templates — or, when they cannot be evaluated, as its fallback.
     */
    fun shapeGeometry(shapeType: String?, custom: CustomShape?, width: Double?, height: Double?): ShapeGeometry {
        val w = size(width, Defaults.WIDTH)
        val h = size(height, Defaults.HEIGHT)
        if (custom != null) {
            val drawn = drawCustomShape(custom, w, h) ?: return shapeGeometry(custom.fallback ?: shapeType, null, w, h)
            return ShapeGeometry("path", listOf("d" to drawn.outline), drawn.details)
        }
        val type = shapeType ?: Defaults.SHAPE_TYPE
        polygonVertices(type, w, h)?.let { return ShapeGeometry("polygon", listOf("points" to points(it))) }
        return when (type) {
            "RoundedRectangle" -> {
                val r = fmt(cornerRadius(w, h))
                ShapeGeometry("rect", listOf("x" to "0", "y" to "0", "width" to fmt(w), "height" to fmt(h), "rx" to r, "ry" to r))
            }
            "Ellipse" -> ShapeGeometry("ellipse", listOf("cx" to fmt(w / 2), "cy" to fmt(h / 2), "rx" to fmt(w / 2), "ry" to fmt(h / 2)))
            "Circle" -> ShapeGeometry("circle", listOf("cx" to fmt(w / 2), "cy" to fmt(h / 2), "r" to fmt(min(w, h) / 2)))
            else -> ShapeGeometry("rect", listOf("x" to "0", "y" to "0", "width" to fmt(w), "height" to fmt(h)))
        }
    }

    /**
     * The shape's outline as a polygon in absolute coordinates, for docking a line's end where it meets the outline
     * drawn: straight edges as they are, arcs and curves flattened finely enough that the end lands on the drawn edge.
     */
    fun outlinePolygon(shapeType: String?, custom: CustomShape?, x: Double, y: Double, width: Double, height: Double): List<Pair<Double, Double>> {
        val w = size(width, Defaults.WIDTH)
        val h = size(height, Defaults.HEIGHT)
        if (custom != null) {
            val drawn = drawCustomShape(custom, w, h)
            if (drawn != null) {
                val flat = PathFlattener.flatten(drawn.outline)
                if (flat.size >= 3) return flat.map { (px, py) -> x + px to y + py }
            }
            return outlinePolygon(custom.fallback ?: shapeType, null, x, y, w, h)
        }
        val type = shapeType ?: Defaults.SHAPE_TYPE
        polygonVertices(type, w, h)?.let { v -> return v.map { (px, py) -> x + px to y + py } }
        fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, steps: Int = 72) =
            (0 until steps).map { i -> val a = 2 * Math.PI * i / steps; cx + rx * Math.cos(a) to cy + ry * Math.sin(a) }
        return when (type) {
            "Ellipse" -> ellipse(x + w / 2, y + h / 2, w / 2, h / 2)
            "Circle" -> ellipse(x + w / 2, y + h / 2, min(w, h) / 2, min(w, h) / 2)
            "RoundedRectangle" -> {
                val r = cornerRadius(w, h)
                val pts = mutableListOf<Pair<Double, Double>>()
                fun arc(cx: Double, cy: Double, from: Double) {
                    for (i in 0..8) { val a = from + Math.PI / 2 * i / 8; pts.add(cx + r * Math.cos(a) to cy + r * Math.sin(a)) }
                }
                arc(x + w - r, y + r, -Math.PI / 2); arc(x + w - r, y + h - r, 0.0); arc(x + r, y + h - r, Math.PI / 2); arc(x + r, y + r, Math.PI)
                pts
            }
            else -> listOf(x to y, x + w to y, x + w to y + h, x to y + h)
        }
    }

    /** Where a shape's badge and label go: the whole shape for a core one; for a notation's own, its content and label boxes. */
    fun shapeLayout(shapeType: String?, custom: CustomShape?, width: Double?, height: Double?): ShapeLayout {
        val w = size(width, Defaults.WIDTH)
        val h = size(height, Defaults.HEIGHT)
        val whole = clampBox(Box(0.0, 0.0, w, h))
        if (custom == null) return ShapeLayout(whole, whole)
        val drawn = drawCustomShape(custom, w, h)
        val content = drawn?.content?.let { clampBox(it) } ?: whole
        val labelBox = drawn?.label ?: return ShapeLayout(content, content)
        val label = clampBox(labelBox)
        val lineHeight = round2(Defaults.FONT_SIZE * 1.2)
        val fits = max(1, min(Defaults.MAX_LABEL_LINES, floor(label.height / lineHeight).toInt()))
        return ShapeLayout(content, label, if (fits < Defaults.MAX_LABEL_LINES) fits else null)
    }

    private fun clampBox(b: Box) = Box(round2(b.x), round2(b.y), round2(max(1.0, b.width)), round2(max(1.0, b.height)))

    // ── A notation's own shapes: path templates ─────────────────────────────

    /**
     * A template expression's value: numbers, the names given (`w`, `h` and the shape's variables), `+ - * /`,
     * parentheses, `min()` and `max()`. Parsed, never evaluated as code.
     */
    fun evaluateExpression(source: String, names: Map<String, Double>): Double {
        val tokens = Regex("""\d+(?:\.\d+)?|\.\d+|[A-Za-z_][A-Za-z0-9_]*|\S""").findAll(source).map { it.value }.toList()
        var at = 0
        fun fail(why: String): Nothing = throw ShapeTemplateError("$why in \"$source\"")
        fun take(expected: String) { if (tokens.getOrNull(at) != expected) fail("expected \"$expected\""); at++ }
        lateinit var sum: () -> Double
        fun unary(): Double {
            val token = tokens.getOrNull(at++) ?: fail("unexpected end")
            if (token == "-") return -unary()
            if (token == "+") return unary()
            if (token == "(") { val v = sum(); take(")"); return v }
            if (token[0].isDigit() || token[0] == '.') return token.toDouble()
            if (token[0].isLetter() || token[0] == '_') {
                if (tokens.getOrNull(at) == "(") {
                    at++
                    val args = mutableListOf(sum())
                    while (tokens.getOrNull(at) == ",") { at++; args.add(sum()) }
                    take(")")
                    return when (token) { "min" -> args.min(); "max" -> args.max(); else -> fail("unknown function \"$token\"") }
                }
                return names[token] ?: fail("unknown name \"$token\"")
            }
            fail("unexpected \"$token\"")
        }
        fun product(): Double {
            var v = unary()
            while (tokens.getOrNull(at) == "*" || tokens.getOrNull(at) == "/") {
                val op = tokens[at++]; val r = unary(); v = if (op == "*") v * r else v / r
            }
            return v
        }
        sum = {
            var v = product()
            while (tokens.getOrNull(at) == "+" || tokens.getOrNull(at) == "-") {
                val op = tokens[at++]; val r = product(); v = if (op == "+") v + r else v - r
            }
            v
        }
        val value = sum()
        if (at < tokens.size) fail("unexpected \"${tokens[at]}\"")
        if (!value.isFinite()) fail("not a finite number")
        return value
    }

    /** A template with each `{expression}` replaced by its value, in the compact form the core shapes' paths use. */
    fun fillTemplate(template: String, names: Map<String, Double>): String =
        template.replace(Regex("""\{([^{}]*)\}""")) { fmt(evaluateExpression(it.groupValues[1], names)) }
            .replace(Regex("""\s+"""), " ").trim()
            .replace(Regex("""\s*([A-Za-z])\s*"""), "$1")

    private fun shapeNames(shape: CustomShape, w: Double, h: Double): Map<String, Double> {
        val names = linkedMapOf("w" to w, "h" to h)
        for (assignment in (shape.variables ?: "").split(';')) {
            if (assignment.isBlank()) continue
            val m = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$""").find(assignment)
            if (m == null || m.groupValues[1] == "w" || m.groupValues[1] == "h") throw ShapeTemplateError("bad assignment \"${assignment.trim()}\"")
            names[m.groupValues[1]] = evaluateExpression(m.groupValues[2], names)
        }
        return names
    }

    private fun fillBox(template: String, names: Map<String, Double>): Box {
        val values = template.replace(Regex("""\{([^{}]*)\}""")) { evaluateExpression(it.groupValues[1], names).toString() }
            .trim().split(Regex("""[\s,]+""")).map { it.toDoubleOrNull() ?: Double.NaN }
        if (values.size != 4 || values.any { !it.isFinite() }) throw ShapeTemplateError("bad box \"$template\"")
        return Box(values[0], values[1], values[2], values[3])
    }

    data class DrawnCustomShape(val outline: String, val details: String?, val content: Box?, val label: Box?)

    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    /** A notation's own shape at w × h; null when its templates cannot be evaluated — it is then drawn as its fallback. */
    fun drawCustomShape(shape: CustomShape, w: Double, h: Double): DrawnCustomShape? = try {
        val names = shapeNames(shape, w, h)
        DrawnCustomShape(
            fillTemplate(shape.outline, names),
            shape.details?.let { fillTemplate(it, names) },
            shape.contentBox?.let { fillBox(it, names) },
            shape.labelBox?.let { fillBox(it, names) },
        )
    } catch (e: ShapeTemplateError) {
        if (reported.add(shape.iri)) log.warn("{}: drawn as its fallback — {}", shape.iri, e.message)
        null
    }

    // ── Icon placement ──────────────────────────────────────────────────────

    /** Where the badge sits for a placement; absent is `TopRight`. */
    fun badgeBox(placement: String?, width: Double, height: Double, badgeSize: Double = Defaults.BADGE_SIZE): BadgeBox {
        val w = size(width, Defaults.WIDTH)
        val h = size(height, Defaults.HEIGHT)
        val b = minOf(badgeSize, w, h)
        val pad = Defaults.BADGE_PADDING
        val centreX = round2((w - b) / 2)
        return when (placement ?: "TopRight") {
            "TopLeft" -> BadgeBox(pad, pad, b)
            "TopCenter" -> BadgeBox(centreX, pad, b)
            "Center" -> BadgeBox(centreX, round2((h - b) / 2), b)
            "BottomCenter" -> BadgeBox(centreX, round2(h - b - pad), b)
            else -> BadgeBox(round2(w - b - pad), pad, b)
        }
    }

    // ── Labels ──────────────────────────────────────────────────────────────

    private val frc = FontRenderContext(null, true, true)
    private val fonts = java.util.concurrent.ConcurrentHashMap<String, Font>()

    /**
     * How wide a line is drawn in a font of its own: measured, as la-gram measures with the browser's canvas text
     * metrics when a look sets a font. Without one la-gram estimates 0.55em a character, and so does this.
     */
    private fun measure(text: String, family: String, size: Double, style: String?): Double {
        val awtStyle = (if (Appearance.hasFontStyle(style, "bold")) Font.BOLD else 0) or (if (Appearance.hasFontStyle(style, "italic")) Font.ITALIC else 0)
        val name = family.split(',').first().trim().trim('"')
        val font = fonts.getOrPut("$name|$awtStyle") { Font(name, awtStyle, 100) }
        return font.getStringBounds(text, frc).width * size / 100
    }

    /** A font of its own to measure in: its family, size and style; null for la-gram's own font. */
    data class TextFont(val family: String, val size: Double, val style: String? = null) {
        val own get() = family != Defaults.FONT_FAMILY || style != null
    }

    /** Lines that fit [availableWidth]: estimated at 0.55em a character, or measured in a font of the label's own. */
    fun wrapLabel(label: String, availableWidth: Double, fontSize: Double = Defaults.FONT_SIZE, maxLines: Int = Defaults.MAX_LABEL_LINES, font: TextFont? = null): List<String> {
        if ('\n' in label) {
            val lines = mutableListOf<String>()
            for (paragraph in label.split('\n')) {
                if (lines.size >= maxLines) break
                lines.addAll(wrapLabel(paragraph, availableWidth, fontSize, maxLines - lines.size, font))
            }
            return lines
        }
        val text = label.trim()
        if (text.isEmpty()) return emptyList()
        if (font != null && font.own) return wrapMeasured(text, availableWidth, maxLines) { measure(it, font.family, font.size, font.style) }
        val perChar = fontSize * 0.55
        val budget = max(3, floor(availableWidth / perChar).toInt())
        if (text.length <= budget) return listOf(text)
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(Regex("\\s+"))) {
            var remaining = word
            while (remaining.length > budget) {
                if (current.isNotEmpty()) { lines.add(current); current = "" }
                lines.add(remaining.substring(0, budget))
                remaining = remaining.substring(budget)
                if (lines.size >= maxLines) return lines.take(maxLines)
            }
            val candidate = if (current.isEmpty()) remaining else "$current $remaining"
            if (candidate.length <= budget) current = candidate
            else {
                lines.add(current)
                current = remaining
                if (lines.size >= maxLines) return lines.take(maxLines)
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines.take(maxLines)
    }

    private fun wrapMeasured(text: String, width: Double, maxLines: Int, measure: (String) -> Double): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        fun fits(line: String) = measure(line) <= width
        for (word in text.split(Regex("\\s+"))) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (fits(candidate)) { current = candidate; continue }
            if (current.isNotEmpty()) {
                lines.add(current)
                if (lines.size >= maxLines) return lines
                current = ""
            }
            var remaining = word
            while (!fits(remaining) && remaining.length > 1) {
                var cut = 1
                while (cut < remaining.length && fits(remaining.substring(0, cut + 1))) cut++
                lines.add(remaining.substring(0, cut))
                if (lines.size >= maxLines) return lines
                remaining = remaining.substring(cut)
            }
            current = remaining
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines.take(maxLines)
    }

    /** How wide a line of text is drawn in la-gram's font: measured, as the browser measures it. */
    fun textWidth(text: String, fontSize: Double = Defaults.FONT_SIZE): Double = measure(text, "SansSerif", fontSize, null)

    /** Whether a shape this size holds one line of its label; a junction's circle does not. */
    fun labelFitsInside(width: Double, height: Double, fontSize: Double = Defaults.FONT_SIZE) =
        width >= fontSize * 3 && height >= round2(fontSize * 1.2)

    /** Where a label goes in a shape, given the badge that shares it. */
    fun labelLayout(
        label: String, width: Double, height: Double,
        badgePlacement: String? = null, fontSize: Double = Defaults.FONT_SIZE, below: Boolean = false, outside: Boolean = false,
        maxLines: Int? = null, align: String? = null, position: String? = null, font: TextFont? = null,
    ): LabelLayout {
        val w = size(width, Defaults.WIDTH)
        val h = size(height, Defaults.HEIGHT)
        val lineHeight = round2(fontSize * 1.2)
        val pad = Defaults.BADGE_PADDING
        val aligned = !outside && !below && (align == "left" || align == "right")
        val bandLeft = if (badgePlacement == "TopLeft") 2 * pad + Defaults.BADGE_SIZE else pad
        val bandRight = if (badgePlacement == "TopRight") w - 2 * pad - Defaults.BADGE_SIZE else w - pad
        val available = when {
            aligned -> max(bandRight - bandLeft, w * 0.5)
            outside -> max(w, Defaults.WIDTH)
            below -> w
            badgePlacement != null -> max(w - Defaults.BADGE_SIZE - 2 * pad, w * 0.5)
            else -> max(w - 2 * pad, w * 0.5)
        }
        val lines = wrapLabel(label, available, fontSize, maxLines ?: Defaults.MAX_LABEL_LINES, font)
        val block = lines.size * lineHeight
        if (outside) return LabelLayout(lines, round2(w / 2), round2(h + pad + fontSize * 0.85), lineHeight)
        val centreY = when {
            below -> h - Defaults.ICON_CENTRIC_LABEL_BAND / 2
            badgePlacement == "Center" -> h - pad - block / 2
            badgePlacement == "BottomCenter" -> pad + block / 2
            position == "top" -> pad + block / 2
            position == "bottom" -> h - pad - block / 2
            else -> h / 2
        }
        val y = round2(centreY - block / 2 + fontSize * 0.85)
        if (aligned && align == "left") return LabelLayout(lines, round2(bandLeft), y, lineHeight, "start")
        if (aligned && align == "right") return LabelLayout(lines, round2(bandRight), y, lineHeight, "end")
        return LabelLayout(lines, round2(w / 2), y, lineHeight)
    }

    // ── Relationship lines ──────────────────────────────────────────────────

    /** `stroke-dasharray` for a line style; null for a solid line. */
    fun strokeDashArray(lineStyle: String?): String? = when (lineStyle) {
        "DashedLine" -> "8 4"
        "DottedLine" -> "2 3"
        else -> null
    }

    /** One decoration inside its marker: hollow glyphs are filled white so the line does not show through them. */
    data class DecorationShape(val tag: String, val attrs: List<Pair<String, String>>, val width: Double, val height: Double, val refX: Double, val refY: Double, val fill: String)

    fun decorationShape(decoration: String?): DecorationShape? = when (decoration) {
        "OpenArrowhead" -> DecorationShape("path", listOf("d" to "M 1 1 L 10 6 L 1 11"), 12.0, 12.0, 10.0, 6.0, "none")
        "ClosedArrowhead" -> DecorationShape("path", listOf("d" to "M 1 1 L 11 6 L 1 11 z"), 12.0, 12.0, 11.0, 6.0, "line")
        "HollowArrowhead" -> DecorationShape("path", listOf("d" to "M 1 1 L 13 7 L 1 13 z"), 14.0, 14.0, 13.0, 7.0, "hollow")
        "HollowDiamond" -> DecorationShape("path", listOf("d" to "M 1 7 L 8 1 L 15 7 L 8 13 z"), 16.0, 14.0, 15.0, 7.0, "hollow")
        "FilledDiamond" -> DecorationShape("path", listOf("d" to "M 1 7 L 8 1 L 15 7 L 8 13 z"), 16.0, 14.0, 15.0, 7.0, "line")
        "HalfArrowhead" -> DecorationShape("path", listOf("d" to "M 1 1 L 11 6 L 3 6"), 12.0, 12.0, 11.0, 6.0, "none")
        "CircleDot" -> DecorationShape("circle", listOf("cx" to "8.5", "cy" to "6", "r" to "3.5"), 12.0, 12.0, 12.0, 6.0, "line")
        else -> null
    }

    fun markerId(decoration: String, end: String, lineColor: String) =
        "la-marker-$decoration-$end-${lineColor.replace(Regex("[^a-zA-Z0-9]"), "").lowercase().ifEmpty { "default" }}"

    /** How much smaller a decoration is drawn on a relationship icon than on a diagram. */
    private const val ICON_MARKER_SCALE = 0.75

    /**
     * A relationship type's icon drawn from its notation, for a type whose notation publishes no picture (neither
     * ArchiMate's relationships nor BPMN's have one): a short line, source at the lower left, in its line style and
     * colour, with its end decorations, as a diagram draws it. A type without a notation gets a plain line. A `data:`
     * URI of a standalone SVG, for an `<img>`, so that its markers' ids are its own. Port of la-gram's
     * `relationshipIconDataUri` (`notation-shapes.ts`).
     */
    fun relationshipIconDataUri(lineColor: String?, lineStyle: String?, sourceDecor: String?, targetDecor: String?): String {
        val color = lineColor ?: Defaults.LINE
        val markers = StringBuilder()
        val ends = StringBuilder()
        for ((end, decoration) in listOf("source" to sourceDecor, "target" to targetDecor)) {
            val shape = decorationShape(decoration) ?: continue
            val fill = when (shape.fill) { "line" -> color; "hollow" -> "#FFFFFF"; else -> "none" }
            val attrs = shape.attrs.joinToString("") { (name, value) -> " $name=\"${xml(value)}\"" }
            markers.append("<marker id=\"$end\" viewBox=\"0 0 ${fmt(shape.width)} ${fmt(shape.height)}\"")
                .append(" markerWidth=\"${fmt(shape.width * ICON_MARKER_SCALE)}\" markerHeight=\"${fmt(shape.height * ICON_MARKER_SCALE)}\"")
                .append(" refX=\"${fmt(shape.refX)}\" refY=\"${fmt(shape.refY)}\" markerUnits=\"userSpaceOnUse\"")
                .append(" orient=\"${if (end == "source") "auto-start-reverse" else "auto"}\">")
                .append("<${shape.tag}$attrs stroke=\"${xml(color)}\" stroke-width=\"2\" fill=\"${xml(fill)}\"/></marker>")
            ends.append(" marker-${if (end == "source") "start" else "end"}=\"url(#$end)\"")
        }
        // A diagram's dashes, shortened to the icon's length.
        val dash = strokeDashArray(lineStyle)?.split(' ')?.joinToString(" ") { fmt(it.toDouble() * 0.6) }
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"24\" height=\"24\">" +
            (if (markers.isNotEmpty()) "<defs>$markers</defs>" else "") +
            "<line x1=\"3\" y1=\"21\" x2=\"21\" y2=\"3\" stroke=\"${xml(color)}\" stroke-width=\"2\"" +
            (if (dash != null) " stroke-dasharray=\"$dash\"" else "") +
            "$ends/></svg>"
        return "data:image/svg+xml;charset=utf-8," + encodeUriComponent(svg)
    }

    /** JavaScript's `encodeURIComponent`: UTF-8, every byte but `A–Z a–z 0–9 - _ . ! ~ * ' ( )` percent-encoded. */
    fun encodeUriComponent(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-_.!~*'()") sb.append(c.toChar())
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }

    /** A published decoration symbol (`arch-vis:decorSymbol`): its viewBox, drawn size and drawing. */
    data class DecorationSymbol(val vx: Double, val vy: Double, val vw: Double, val vh: Double, val width: Double, val height: Double, val content: String)

    private val symbolElements = setOf("g", "path", "polygon", "polyline", "circle", "ellipse", "rect", "line")
    private val symbolAttributes = setOf("d", "points", "cx", "cy", "r", "rx", "ry", "x", "y", "width", "height", "x1", "y1", "x2", "y2", "transform",
        "fill", "fill-rule", "fill-opacity", "stroke", "stroke-width", "stroke-linejoin", "stroke-linecap", "stroke-dasharray", "stroke-opacity", "opacity")

    /**
     * A published decoration symbol read into its parts, or null when it is not a usable SVG. It is drawn inside the
     * page, so it is rebuilt rather than inserted: only plain shape elements with their geometry and paint survive.
     */
    fun parseDecorationSymbol(svgText: String): DecorationSymbol? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = factory.newDocumentBuilder().parse(svgText.byteInputStream()).documentElement
        if (root.localName != "svg") null else {
            fun nums(v: String) = v.trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
            fun dim(v: String) = v.toDoubleOrNull()?.takeIf { it > 0 }
            val vb = nums(root.getAttribute("viewBox"))
            val width = dim(root.getAttribute("width"))
            val height = dim(root.getAttribute("height"))
            val view = if (vb.size == 4 && vb[2] > 0 && vb[3] > 0) vb else if (width != null && height != null) listOf(0.0, 0.0, width, height) else null
            if (view == null) null else {
                fun rebuild(e: org.w3c.dom.Element): String? {
                    if (e.localName !in symbolElements) return null
                    val attrs = buildString {
                        for (i in 0 until e.attributes.length) {
                            val a = e.attributes.item(i)
                            if (a.nodeName !in symbolAttributes || Regex("url\\(|javascript:", RegexOption.IGNORE_CASE).containsMatchIn(a.nodeValue)) continue
                            append(" ").append(a.nodeName).append("=\"").append(xml(a.nodeValue)).append("\"")
                        }
                    }
                    val children = (0 until e.childNodes.length).mapNotNull { (e.childNodes.item(it) as? org.w3c.dom.Element)?.let(::rebuild) }.joinToString("")
                    return "<${e.localName}$attrs>$children</${e.localName}>"
                }
                val content = (0 until root.childNodes.length).mapNotNull { (root.childNodes.item(it) as? org.w3c.dom.Element)?.let(::rebuild) }.joinToString("")
                if (content.isEmpty()) null else DecorationSymbol(view[0], view[1], view[2], view[3], width ?: view[2], height ?: view[3], content)
            }
        }
    } catch (_: Exception) { null }

    /**
     * The `<marker>` for a decoration at an end in a colour: drawn from its published symbol when there is one —
     * pointing in +x, met at the middle of its viewBox's right edge, mirrored at a source end, `currentColor` the
     * line's colour — else from the built-in geometry.
     */
    fun markerSvg(decoration: String, end: String, lineColor: String, symbol: DecorationSymbol?): String? {
        val id = markerId(decoration, end, lineColor)
        val orient = if (end == "source") "auto-start-reverse" else "auto"
        if (symbol != null) {
            return "<marker id=\"$id\" class=\"la-marker la-marker-$decoration\" viewBox=\"${fmt(symbol.vx)} ${fmt(symbol.vy)} ${fmt(symbol.vw)} ${fmt(symbol.vh)}\"" +
                " markerWidth=\"${fmt(symbol.width)}\" markerHeight=\"${fmt(symbol.height)}\" refX=\"${fmt(symbol.vx + symbol.vw)}\" refY=\"${fmt(symbol.vy + symbol.vh / 2)}\"" +
                " markerUnits=\"userSpaceOnUse\" orient=\"$orient\" color=\"${xml(lineColor)}\">${symbol.content}</marker>"
        }
        val shape = decorationShape(decoration) ?: return null
        val fill = when (shape.fill) { "line" -> lineColor; "hollow" -> "#FFFFFF"; else -> "none" }
        val attrs = shape.attrs.joinToString("") { (k, v) -> " $k=\"$v\"" }
        return "<marker id=\"$id\" class=\"la-marker la-marker-$decoration\" markerWidth=\"${fmt(shape.width)}\" markerHeight=\"${fmt(shape.height)}\"" +
            " refX=\"${fmt(shape.refX)}\" refY=\"${fmt(shape.refY)}\" markerUnits=\"userSpaceOnUse\" orient=\"$orient\">" +
            "<${shape.tag}$attrs stroke=\"${xml(lineColor)}\" stroke-width=\"${fmt(Defaults.STROKE_WIDTH)}\" fill=\"${xml(fill)}\"/></marker>"
    }

    /**
     * A relationship type's icon drawn from its notation: a short line from the lower left, in its line style and
     * colour, with its end decorations — as la-gram's palette draws one for a type that publishes no picture.
     */
    fun relationshipIconSvg(style: RelationshipStyle?): String {
        val color = style?.lineColor ?: Defaults.LINE
        val markers = StringBuilder()
        val ends = StringBuilder()
        for ((end, decoration) in listOf("source" to style?.sourceDecor, "target" to style?.targetDecor)) {
            val shape = decorationShape(decoration) ?: continue
            val fill = when (shape.fill) { "line" -> color; "hollow" -> "#FFFFFF"; else -> "none" }
            val attrs = shape.attrs.joinToString("") { (k, v) -> " $k=\"${xml(v)}\"" }
            markers.append("<marker id=\"$end\" viewBox=\"0 0 ${fmt(shape.width)} ${fmt(shape.height)}\" markerWidth=\"${fmt(shape.width * 0.75)}\" markerHeight=\"${fmt(shape.height * 0.75)}\"")
                .append(" refX=\"${fmt(shape.refX)}\" refY=\"${fmt(shape.refY)}\" markerUnits=\"userSpaceOnUse\" orient=\"${if (end == "source") "auto-start-reverse" else "auto"}\">")
                .append("<${shape.tag}$attrs stroke=\"${xml(color)}\" stroke-width=\"2\" fill=\"${xml(fill)}\"/></marker>")
            ends.append(" marker-${if (end == "source") "start" else "end"}=\"url(#$end)\"")
        }
        val dash = strokeDashArray(style?.lineStyle)?.split(" ")?.joinToString(" ") { fmt(it.toDouble() * 0.6) }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"24\" height=\"24\">" +
            (if (markers.isNotEmpty()) "<defs>$markers</defs>" else "") +
            "<line x1=\"3\" y1=\"21\" x2=\"21\" y2=\"3\" stroke=\"${xml(color)}\" stroke-width=\"2\"" + (dash?.let { " stroke-dasharray=\"$it\"" } ?: "") + "$ends/></svg>"
    }

    /** An element type's icon drawn from its notation: its figure in its colours, for a type that publishes no picture. */
    fun elementIconSvg(style: VisualStyle?): String {
        val width = 22.0
        val height = 15.0
        val fill = style?.fillColor ?: Defaults.FILL
        val line = style?.lineColor ?: Defaults.LINE
        val g = shapeGeometry(style?.shapeType, style?.customShape, width, height)
        val attrs = g.attrs.joinToString("") { (k, v) -> " $k=\"${xml(v)}\"" }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"24\" height=\"24\">" +
            "<g transform=\"translate(1 ${fmt((24 - height) / 2)})\" stroke=\"${xml(line)}\" stroke-width=\"1.2\">" +
            "<${g.tag}$attrs fill=\"${xml(fill)}\"/>" + (g.details?.let { "<path d=\"${xml(it)}\" fill=\"none\"/>" } ?: "") + "</g></svg>"
    }
}

/**
 * Absolute SVG path data flattened into points: lines as they are, curves and arcs sampled. Used to find where a line
 * meets a notation's own outline; a path with relative commands is read as written, each relative to the last point.
 */
object PathFlattener {
    fun flatten(d: String): List<Pair<Double, Double>> {
        val tokens = Regex("""[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(d).map { it.value }.toList()
        val pts = mutableListOf<Pair<Double, Double>>()
        var i = 0
        var cmd = ""
        var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0
        fun num() = tokens[i++].toDouble()
        fun more() = i < tokens.size && !tokens[i][0].isLetter()
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) cmd = tokens[i++]
            val rel = cmd == cmd.lowercase()
            when (cmd.uppercase()) {
                "M" -> { x = num() + if (rel) x else 0.0; y = num() + if (rel) y else 0.0; sx = x; sy = y; pts.add(x to y); cmd = if (rel) "l" else "L" }
                "L", "T" -> { x = num() + if (rel) x else 0.0; y = num() + if (rel) y else 0.0; pts.add(x to y) }
                "H" -> { x = num() + if (rel) x else 0.0; pts.add(x to y) }
                "V" -> { y = num() + if (rel) y else 0.0; pts.add(x to y) }
                "Q" -> {
                    val cx = num() + if (rel) x else 0.0; val cy = num() + if (rel) y else 0.0
                    val ex = num() + if (rel) x else 0.0; val ey = num() + if (rel) y else 0.0
                    for (k in 1..12) { val t = k / 12.0; val u = 1 - t
                        pts.add(u * u * x + 2 * u * t * cx + t * t * ex to u * u * y + 2 * u * t * cy + t * t * ey) }
                    x = ex; y = ey
                }
                "C" -> {
                    val c1x = num() + if (rel) x else 0.0; val c1y = num() + if (rel) y else 0.0
                    val c2x = num() + if (rel) x else 0.0; val c2y = num() + if (rel) y else 0.0
                    val ex = num() + if (rel) x else 0.0; val ey = num() + if (rel) y else 0.0
                    for (k in 1..16) { val t = k / 16.0; val u = 1 - t
                        pts.add(u * u * u * x + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * ex to
                            u * u * u * y + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * ey) }
                    x = ex; y = ey
                }
                "S" -> { // Treated as a cubic with its first control point at the current point.
                    val c2x = num() + if (rel) x else 0.0; val c2y = num() + if (rel) y else 0.0
                    val ex = num() + if (rel) x else 0.0; val ey = num() + if (rel) y else 0.0
                    for (k in 1..16) { val t = k / 16.0; val u = 1 - t
                        pts.add(u * u * u * x + 3 * u * u * t * x + 3 * u * t * t * c2x + t * t * t * ex to
                            u * u * u * y + 3 * u * u * t * y + 3 * u * t * t * c2y + t * t * t * ey) }
                    x = ex; y = ey
                }
                "A" -> { // Sampled along the chord's arc approximately: radii and flags are rarely needed for docking.
                    num(); num(); num(); num(); num()
                    val ex = num() + if (rel) x else 0.0; val ey = num() + if (rel) y else 0.0
                    for (k in 1..8) { val t = k / 8.0; pts.add(x + (ex - x) * t to y + (ey - y) * t) }
                    x = ex; y = ey
                }
                // Numbers after a close are no path this reads: what was read so far is the outline.
                "Z" -> { x = sx; y = sy; if (more()) return pts }
                else -> return pts
            }
        }
        return pts
    }
}
