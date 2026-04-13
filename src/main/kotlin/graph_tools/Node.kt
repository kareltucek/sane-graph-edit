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
        var lines: List<String> = emptyList(),
        /**
         * Filtering / hide level. 0 = visible. Each `h` command
         * increments this on every non-selected node (even already
         * hidden ones); each `H` decrements all nodes where it's >0.
         * The level acts as a stack counter: two hides followed by
         * one unhide restores only the second hide's victims.
         *
         * View state only — not serialised to DOT, lost on file
         * load (all nodes start visible).
         */
        var hideLevel: Int = 0,
        /**
         * Session-only marks (lowercase a-z). Concatenated letters,
         * e.g. `"abf"`. View state, not serialised to DOT — lost on
         * save/load.
         */
        var sessionMarks: String = "",
    )

    /** True when this node should be drawn and is clickable. */
    val isVisible: Boolean get() = cache.hideLevel == 0

    /**
     * Get the mark string for [mark]'s case: uppercase reads
     * persistent [NodeAttributes.marks], lowercase reads transient
     * [NodeCache.sessionMarks].
     */
    fun getMarks(uppercase: Boolean): String =
        if (uppercase) attributes.marks else cache.sessionMarks

    fun setMarks(uppercase: Boolean, value: String) {
        if (uppercase) attributes.marks = value else cache.sessionMarks = value
    }

    /** True if this node carries [mark] in the appropriate storage. */
    fun hasMark(mark: Char): Boolean =
        getMarks(mark.isUpperCase()).contains(mark)

    class NodeAttributes(
        var name: String = "",
        var text: String = "",
        var bg: Color? = null,
        var fg: Color? = null,
        var nodeScale: Double? = null,
        /**
         * Persistent marks (uppercase A-Z). Concatenated letters,
         * e.g. `"ABF"`. Round-trips through DOT as a custom
         * `marks="..."` attribute.
         */
        var marks: String = "",
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
            "marks" -> attributes.marks = r
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
            "marks" to attributes.marks.takeIf { it.isNotEmpty() },
            "pos" to "${position.x.toNiceString()},${position.y.toNiceString()}!",
        ).filterNotNull() + attributes.other.filterNotNull()
    }

    fun setSize(absolute: Double? = null, relative: Double? = null) {
        this.attributes.nodeScale = this.attributes.nodeScale
            .orElse(0.0)
            .letIf(absolute != null) { absolute!! }
            .letIf(relative != null) { it + relative!! }
    }

    fun getStyle(): NodeStyle {
        return NodeStyle.fromNode(this)
    }

    constructor(label: String, pos: Vector2) : this(position = pos, attributes = NodeAttributes(text = label))

    constructor(label: String, pos: Vector2, style: NodeStyle) : this(
        position = pos,
        attributes = NodeAttributes(text = label, style)
    )

    constructor(name: String) : this(position = Vector2.Zero, attributes = NodeAttributes(name = name, text = name))

}