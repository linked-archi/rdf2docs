package archi.linked.rdf2docs.notation

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Value
import java.net.URI
import kotlin.math.roundToInt

/**
 * How one thing drawn in a view looks, set on its own core-vis style (`arch-vis:style`), or what a notation's style
 * says of the same keys. Each value absent leaves the notation's choice, or la-gram's. Ported from the kernel's
 * `model-manager/appearance.ts`, which la-gram draws with: the keys, their predicates and how a value is read are the
 * same, so a placement looks here as it does there.
 */
data class Appearance(
    val fillColor: String? = null,
    val gradient: String? = null,
    val lineColor: String? = null,
    val fontColor: String? = null,
    val lineWidth: Double? = null,
    val borderStyle: String? = null,
    val iconColor: String? = null,
    val fontName: String? = null,
    val fontSize: Double? = null,
    val fontStyle: String? = null,
    val textAlignment: String? = null,
    val textPosition: String? = null,
    val renderingMode: String? = null,
    val shapeType: String? = null,
    val lineStyle: String? = null,
    val sourceDecor: String? = null,
    val targetDecor: String? = null,
    val labelAnchor: String? = null,
    val image: String? = null,
    val imagePosition: String? = null,
    val iconSymbol: String? = null,
    val useElementPicture: Boolean? = null,
) {
    /** Whether it sets anything at all. */
    fun isEmpty() = this == NONE

    /** This look with [top]'s values over it, key by key. */
    fun overlay(top: Appearance?): Appearance {
        if (top == null) return this
        return Appearance(
            fillColor = top.fillColor ?: fillColor,
            gradient = top.gradient ?: gradient,
            lineColor = top.lineColor ?: lineColor,
            fontColor = top.fontColor ?: fontColor,
            lineWidth = top.lineWidth ?: lineWidth,
            borderStyle = top.borderStyle ?: borderStyle,
            iconColor = top.iconColor ?: iconColor,
            fontName = top.fontName ?: fontName,
            fontSize = top.fontSize ?: fontSize,
            fontStyle = top.fontStyle ?: fontStyle,
            textAlignment = top.textAlignment ?: textAlignment,
            textPosition = top.textPosition ?: textPosition,
            renderingMode = top.renderingMode ?: renderingMode,
            shapeType = top.shapeType ?: shapeType,
            lineStyle = top.lineStyle ?: lineStyle,
            sourceDecor = top.sourceDecor ?: sourceDecor,
            targetDecor = top.targetDecor ?: targetDecor,
            labelAnchor = top.labelAnchor ?: labelAnchor,
            image = top.image ?: image,
            imagePosition = top.imagePosition ?: imagePosition,
            iconSymbol = top.iconSymbol ?: iconSymbol,
            useElementPicture = top.useElementPicture ?: useElementPicture,
        )
    }

    /**
     * The look a style says, without what is not a look to take over: a notation's shape type is the shape's local
     * name, not a placement's IRI, and its icon is its glyph, not a placement's own picture (`lookOf`).
     */
    fun look(): Appearance = copy(shapeType = null, iconSymbol = null, useElementPicture = null)

    companion object {
        val NONE = Appearance()

        private val ENUMS: Map<String, Map<String, String>> = mapOf(
            "borderStyle" to mapOf("SolidLine" to "SolidLine", "DashedLine" to "DashedLine", "DottedLine" to "DottedLine"),
            "lineStyle" to mapOf("SolidLine" to "SolidLine", "DashedLine" to "DashedLine", "DottedLine" to "DottedLine"),
            "textAlignment" to mapOf("AlignLeft" to "left", "AlignCenter" to "center", "AlignRight" to "right"),
            "textPosition" to mapOf("PositionTop" to "top", "PositionMiddle" to "middle", "PositionBottom" to "bottom"),
            "gradient" to mapOf("GradientTop" to "top", "GradientLeft" to "left", "GradientRight" to "right", "GradientBottom" to "bottom"),
            "renderingMode" to mapOf("ShapeWithBadge" to "ShapeWithBadge", "IconCentric" to "IconCentric", "ShapeOnly" to "ShapeOnly"),
            "sourceDecor" to DECORATIONS.associateWith { it },
            "targetDecor" to DECORATIONS.associateWith { it },
            "labelAnchor" to mapOf("AnchorSource" to "source", "AnchorMiddle" to "middle", "AnchorTarget" to "target"),
            "imagePosition" to listOf("TopLeft", "TopCenter", "TopRight", "MiddleLeft", "Center", "MiddleRight", "BottomLeft", "BottomCenter", "BottomRight", "Fill").associateWith { it },
        )

        /** An enumeration's member, read from its core-vis IRI; null for anything that is not one of the key's. */
        private fun member(key: String, value: Value): String? {
            val text = value.stringValue().trim()
            if (!text.startsWith(NS.VIS)) return null
            return ENUMS[key]?.get(text.removePrefix(NS.VIS))
        }

        private fun positive(value: Value): Double? = value.stringValue().trim().toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

        /** What a style says of how its node, its line or its notation's figures look. */
        fun read(t: Triples, style: Resource): Appearance {
            fun v(key: String): Value? = t.first(style, NS.VIS + key)
            fun colour(key: String) = v(key)?.stringValue()?.trim()?.takeIf { it.isNotEmpty() }?.let { cssColour(it) }
            fun enum(key: String) = v(key)?.let { member(key, it) }
            fun uri(key: String) = (v(key) as? Literal)?.let { imageUri(it.stringValue()) }
            return Appearance(
                fillColor = colour("fillColor"),
                gradient = enum("gradient"),
                lineColor = colour("lineColor"),
                fontColor = colour("fontColor"),
                lineWidth = v("lineWidth")?.let { positive(it) },
                borderStyle = enum("borderStyle"),
                iconColor = colour("iconColor"),
                fontName = v("fontName")?.stringValue()?.trim()?.takeIf { it.isNotEmpty() },
                fontSize = v("fontSize")?.let { positive(it) },
                fontStyle = v("fontStyle")?.let { fontStyleOf(it.stringValue()) },
                textAlignment = enum("textAlignment"),
                textPosition = enum("textPosition"),
                renderingMode = enum("renderingMode"),
                shapeType = (v("shapeType") as? IRI)?.stringValue(),
                lineStyle = enum("lineStyle"),
                sourceDecor = enum("sourceDecor"),
                targetDecor = enum("targetDecor"),
                labelAnchor = enum("labelAnchor"),
                image = uri("image"),
                imagePosition = enum("imagePosition"),
                iconSymbol = uri("iconSymbol"),
                useElementPicture = (v("useElementPicture") as? Literal)?.stringValue()?.let {
                    when (it) { "true", "1" -> true; "false", "0" -> false; else -> null }
                },
            )
        }

        /**
         * A picture's address as drawn: an http(s) URL, or a `data:` URI of an image — null for anything else (a
         * `javascript:` or a `file:` URI, a relative path), which is never drawn.
         */
        fun imageUri(value: String?): String? {
            val uri = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (Regex("^data:image/[a-z0-9.+-]+[;,]", RegexOption.IGNORE_CASE).containsMatchIn(uri)) return uri
            return try {
                val scheme = URI(uri).scheme?.lowercase()
                if (scheme == "http" || scheme == "https") uri else null
            } catch (_: Exception) { null }
        }

        /** A font style's keywords, known ones only, in order; null for plain. */
        fun fontStyleOf(style: String?): String? {
            val words = (style ?: "").lowercase().split(Regex("\\s+")).toSet()
            return listOf("bold", "italic", "underline").filter { it in words }.joinToString(" ").ifEmpty { null }
        }

        fun hasFontStyle(style: String?, word: String) = (style ?: "").split(Regex("\\s+")).contains(word)

        /**
         * A colour as CSS should draw it: `#rrggbb`, or `#rrggbbaa` when it is not opaque — an `rgba()` alpha above 1
         * read as the exchange format's percentage (up to 100) or out of 255, as the converters wrote it before
         * core-vis 0.7 said CSS's 0–1. A colour this cannot read (a name: `red`) is kept as it is.
         */
        fun cssColour(colour: String): String {
            val parts = parseColour(colour) ?: return colour.trim()
            val hex = "#" + listOf(parts[0], parts[1], parts[2]).joinToString("") { it.roundToInt().toString(16).padStart(2, '0') }
            val alpha = parts[3]
            return if (alpha >= 1) hex else hex + (alpha * 255).roundToInt().toString(16).padStart(2, '0')
        }

        /** Red, green and blue (0–255) and alpha (0–1); null for what cannot be read. */
        fun parseColour(colour: String?): DoubleArray? {
            val value = colour?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            Regex("^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$").find(value)?.let { m ->
                val hex = m.groupValues[1]
                val full = if (hex.length <= 4) hex.map { "$it$it" }.joinToString("") else hex
                fun byte(i: Int) = full.substring(i * 2, i * 2 + 2).toInt(16).toDouble()
                return doubleArrayOf(byte(0), byte(1), byte(2), if (full.length == 8) byte(3) / 255 else 1.0)
            }
            val rgb = Regex("^rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*([\\d.]+)\\s*(%?)\\s*)?\\)$").find(value) ?: return null
            fun channel(i: Int) = minOf(255.0, rgb.groupValues[i].toDouble())
            val alphaText = rgb.groupValues[4]
            val alpha = when {
                alphaText.isEmpty() -> 1.0
                rgb.groupValues[5] == "%" -> (alphaText.toDouble() / 100).coerceIn(0.0, 1.0)
                alphaText.toDouble() <= 1 -> alphaText.toDouble().coerceIn(0.0, 1.0)
                alphaText.toDouble() <= 100 -> alphaText.toDouble() / 100
                else -> (alphaText.toDouble() / 255).coerceIn(0.0, 1.0)
            }
            return doubleArrayOf(channel(1), channel(2), channel(3), alpha)
        }
    }
}
