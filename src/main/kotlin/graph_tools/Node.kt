package graph_tools

import ui.ColorStyle
import ui.NodeStyle
import utils.Utils
import utils.Utils.filterNotNull
import utils.Utils.letIf
import utils.Utils.orElse
import utils.Utils.toHexString
import utils.Constants
import utils.Utils.toNiceString
import utils.Vector2
import java.awt.Color
import java.lang.Math.pow
import kotlin.math.log

class Node(
    var position: Vector2,
    var cache: NodeCache = NodeCache(),
    var attributes: NodeAttributes = NodeAttributes(),
) {
    override fun toString(): String {
        return "Node(...${attributes.text.take(8)}...)"
    }

    data class NodeCache(
        var textBounds: Vector2 = Vector2.Zero,
        var shapeBounds: Vector2 = Vector2.Zero,
        var shape: NodeShapeImpl = NodeShape.Rectangle.impl,
        var font: FontData? = null,
        var lines: List<String> = emptyList()
    )

    class NodeAttributes(
        var name: String = "",
        var text: String = "",
        var bg: Color? = null,
        var fg: Color? = null,
        var nodeScale: Double? = null,
        var other: MutableMap<String, String?> = mutableMapOf(),
    ) {
        constructor(text: String, style: NodeStyle) : this(
            text = text,
            bg = style.background,
            fg = style.foreground,
            nodeScale = style.nodeScale
        )
    }

    fun setStyle(style: ColorStyle) {
        this.attributes.bg = style.background
        this.attributes.fg = style.foreground
    }

    fun setShape(shape: NodeShape?) {
        this.cache.shape = shape?.impl.orElse(NodeShape.defaultShape.impl)
        this.attributes.other["shape"] = shape?.id
    }

    fun setStyle(style: NodeStyle) {
        this.attributes.bg = style.background
        this.attributes.fg = style.foreground
        this.attributes.nodeScale = style.nodeScale
    }

    fun applyAttribute(l: String, r: String) {
        when (l) {
            "shape" -> {
                this.cache.shape = NodeShape.values().find { it.id == r }?.impl.orElse(NodeShape.defaultShape.impl)
                attributes.other[l] = r
            }

            "fillcolor" -> attributes.bg = Utils.fromHexString(r)
            "color" -> attributes.fg = Utils.fromHexString(r)
            "label" -> attributes.text = r
            "fontsize" -> attributes.nodeScale = log(r.toDouble()/Plotter.defaultFontSize, Constants.fontSizeZoomCoef)
            "pos" -> r
                .replace("!", "")
                .split(",")
                .map { it.toDouble() }
                .takeIf { it.size == 2 }
                ?.let {
                    position = Vector2(it[0], it[1])
                }

            else -> attributes.other[l] = r
        }
    }

    fun retrieveAttributes(): Map<String, String> {
        return mapOf(
            "fillcolor" to attributes.bg?.toHexString(),
            "color" to attributes.fg?.toHexString(),
            "fontsize" to attributes.nodeScale?.let { pow(Constants.fontSizeZoomCoef, it)*Plotter.defaultFontSize }?.toNiceString(),
            "label" to attributes.text,
            "pos" to "${position.x.toNiceString()},${position.y.toNiceString()}!",
        ).filterNotNull() + attributes.other.filterNotNull()
    }

    fun setSize(absolute: Double? = null, relative: Double? = null) {
        this.attributes.nodeScale = this.attributes.nodeScale
            .orElse(0.0)
            .letIf(absolute != null) { absolute!! }
            .letIf(relative != null) { it + relative!! }
    }

    constructor(label: String, pos: Vector2) : this(position = pos, attributes = NodeAttributes(text = label))

    constructor(label: String, pos: Vector2, style: NodeStyle) : this(
        position = pos,
        attributes = NodeAttributes(text = label, style)
    )

    constructor(name: String) : this(position = Vector2.Zero, attributes = NodeAttributes(name = name, text = name))

}