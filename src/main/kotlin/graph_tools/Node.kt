package graph_tools

import ui.Utils
import ui.Utils.filterNotNull
import ui.Utils.letIf
import ui.Utils.orElse
import ui.Utils.toHexString
import utils.Constants
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
        var scale: Double? = null,
        var other: MutableMap<String, String?> = mutableMapOf(),
    ) {
    }

    fun setStyle(bg: Color?, fg: Color?) {
        this.attributes.bg = bg
        this.attributes.fg = fg
    }

    fun setShape(shape: NodeShape?) {
        this.cache.shape = shape?.impl.orElse(NodeShape.defaultShape.impl)
        this.attributes.other["shape"] = shape?.id
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
            "fontsize" -> attributes.scale = log(r.toDouble(), Constants.fontSizeZoomCoef)
            "pos" -> r.split(",")
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
            "fontsize" to attributes.scale?.let { pow(Constants.fontSizeZoomCoef, it)}?.toString(),
            "label" to attributes.text,
            "pos" to "${position.x},${position.y}",
        ).filterNotNull() + attributes.other.filterNotNull()
    }

    fun setSize(absolute: Double? = null, relative: Double? = null) {
        this.attributes.scale = this.attributes.scale
            .orElse(0.0)
            .letIf (absolute != null) { absolute!! }
            .letIf (relative != null) { it + relative!! }
    }

    constructor(label: String, pos: Vector2) : this(position = pos, attributes = NodeAttributes(text = label))
    constructor(name: String) : this(position = Vector2.Zero, attributes = NodeAttributes(name = name, text = name))

}