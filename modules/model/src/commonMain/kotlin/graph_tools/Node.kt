package graph_tools

import utils.Utils
import utils.Utils.filterNotNull
import utils.Utils.letIf
import utils.Utils.orElse
import utils.Utils.toHexString
import utils.Constants
import utils.Utils.toNiceString
import utils.Vector2
import utils.Vector3
import utils.Rgb
import kotlin.math.pow
import kotlin.math.log

class Node(
    position: Vector2,
    var cache: NodeCache = NodeCache(),
    var attributes: NodeAttributes = NodeAttributes(),
    /** Stable for the node's life; the same node has the same id in every client's replica. */
    val id: String = Ids.new(),
) {
    /** Where the node is: across, down, and its depth ([Depth]). The node itself is drawn flat, facing the viewer. */
    var point: Vector3 = Vector3(position, 0.0)

    /** Where the node is in the plane the graph is drawn in: [point] without its depth, which setting it keeps. */
    var position: Vector2
        get() = point.xy
        set(value) {
            point = Vector3(value, point.z)
        }

    override fun toString(): String {
        return "Node(...${attributes.text.take(8)}...)"
    }

    data class NodeCache(
        var textBounds: Vector2 = Vector2.Zero,
        var shapeBounds: Vector2 = Vector2.Zero,
        var shape: NodeShapeImpl = NodeShape.Rectangle.impl,
        var lines: List<String> = emptyList(),
        /** Font ascent and line height the text was measured with, in workspace units. */
        var ascent: Double = 0.0,
        var lineHeight: Double = 0.0,
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

    /**
     * True when hidden with `h`. Whether the node is drawn and
     * clickable also depends on [ViewModes]; ask [Graph.isVisible].
     */
    val isHidden: Boolean get() = cache.hideLevel > 0

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
        var bg: Rgb? = null,
        var fg: Rgb? = null,
        var nodeScale: Double? = null,
        /**
         * Persistent marks (uppercase A-Z). Concatenated letters,
         * e.g. `"ABF"`. Round-trips through DOT as a custom
         * `marks="..."` attribute.
         */
        var marks: String = "",
        var other: MutableMap<String, String?> = mutableMapOf(),
        /** False when the node has no label at the level of detail it is at ([Detail.text]); an empty [text] may still be a label. */
        var labelled: Boolean = true,
    ) {
        constructor(text: String, style: NodeStyle) : this(
            text = text,
            bg = style.background,
            fg = style.foreground,
            nodeScale = style.nodeScale
        )
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

    /**
     * What a node has once for every level of detail: in DOT the
     * attributes `label`, `content-type`, `fontsize`, `color` and
     * `fillcolor`, with `-l<level>` after the name (none, or `-l0`,
     * is level 0). Null is "not given at this level": nothing is
     * taken from another level, the defaults are used.
     */
    data class Detail(
        val text: String? = null,
        val contentType: String? = null,
        val bg: Rgb? = null,
        val fg: Rgb? = null,
        val scale: Double? = null,
    ) {
        /** A node is not shown at a level where it has neither a label nor a content type. */
        val isBlank: Boolean get() = text == null && contentType == null

        /** This with the DOT attribute [name] set to [value]. */
        fun with(name: String, value: String): Detail = when (name) {
            "label" -> copy(text = value)
            NodeType.ATTRIBUTE -> copy(contentType = value)
            "fillcolor" -> copy(bg = Utils.fromHexString(value))
            "color" -> copy(fg = Utils.fromHexString(value))
            else -> copy(scale = log(value.toDouble() / Constants.defaultFontSize, Constants.fontSizeZoomCoef))
        }

        /** As DOT attributes, without the level in their names; in the order they are written in. */
        fun attributes(): Map<String, String> = mapOf(
            "fillcolor" to bg?.toHexString(),
            "color" to fg?.toHexString(),
            "fontsize" to scale?.let { Constants.fontSizeZoomCoef.pow(it) * Constants.defaultFontSize }?.toNiceString(),
            "label" to text,
            NodeType.ATTRIBUTE to contentType,
        ).filterNotNull()

        companion object {
            val None = Detail()
            val NAMES = setOf("label", NodeType.ATTRIBUTE, "fontsize", "color", "fillcolor")
            private val LEVELLED = Regex("(.+)-l(\\d{1,3})")

            /** A name as it is written, as the name proper and the level: `label-l2` is `label` at 2, `label` is it at 0. */
            fun split(written: String, names: Set<String> = NAMES): Pair<String, Int> =
                LEVELLED.matchEntire(written)
                    ?.destructured
                    ?.let { (name, level) -> name to level.toInt() }
                    ?.takeIf { it.first in names }
                    ?: (written to 0)

            fun written(name: String, level: Int): String = if (level == 0) name else "$name-l$level"
        }
    }

    /**
     * The level of detail whose [Detail] is in [attributes]; those
     * of the other levels are put aside. The graph's
     * ([Graph.level]), kept here too so that a node can be read and
     * written on its own.
     */
    var level: Int = 0

    private val aside = mutableMapOf<Int, Detail>()

    /** Whether DOT gave this node a label, rather than its name standing in for one. */
    private var labelGiven = false

    /** The levels this node has something at. */
    val levels: List<Int> get() = (aside.keys + listOfNotNull(level.takeIf { detail() != Detail.None })).sorted()

    fun detail(at: Int = level): Detail = when (at) {
        level -> Detail(
            text = attributes.text.takeIf { attributes.labelled || it.isNotEmpty() },
            contentType = attributes.other[NodeType.ATTRIBUTE],
            bg = attributes.bg,
            fg = attributes.fg,
            scale = attributes.nodeScale,
        )
        else -> aside[at] ?: Detail.None
    }

    fun setDetail(at: Int, d: Detail) {
        when {
            at == level -> {
                attributes.text = d.text.orEmpty()
                attributes.labelled = d.text != null
                attributes.bg = d.bg
                attributes.fg = d.fg
                attributes.nodeScale = d.scale
                if (d.contentType == null) attributes.other.remove(NodeType.ATTRIBUTE) else attributes.other[NodeType.ATTRIBUTE] = d.contentType
            }
            d == Detail.None -> aside.remove(at)
            else -> aside[at] = d
        }
    }

    /** Be at level [to]: what the node has there comes to [attributes], what it has here is put aside. */
    fun showLevel(to: Int) {
        if (to != level) {
            val here = detail()
            val there = detail(to)
            aside.remove(to)
            if (here != Detail.None) aside[level] = here
            level = to
            setDetail(to, there)
        }
    }

    fun applyAttribute(written: String, r: String) {
        val (l, at) = Detail.split(written)
        val elsewhere = l in Detail.NAMES && at != level
        if (elsewhere) {
            setDetail(at, detail(at).with(l, r))
            // in DOT a node without a label shows its name; one that is told what it is at
            // another level and not at this one has nothing here
            if (!labelGiven && attributes.text == attributes.name) setDetail(level, detail().copy(text = null))
        } else when (l) {
            "shape" -> {
                this.cache.shape = NodeShape.values().find { it.id == r }?.impl.orElse(NodeShape.defaultShape.impl)
                attributes.other[l] = r
            }

            "fillcolor" -> attributes.bg = Utils.fromHexString(r)
            "color" -> attributes.fg = Utils.fromHexString(r)
            "label" -> {
                attributes.text = r
                attributes.labelled = true
                labelGiven = true
            }
            "marks" -> attributes.marks = r
            "fontsize" -> attributes.nodeScale = log(r.toDouble()/Constants.defaultFontSize, Constants.fontSizeZoomCoef)
            "pos" -> r
                .replace("!", "")
                .split(",")
                .map { it.toDouble() }
                .takeIf { it.size == 2 || it.size == 3 }
                ?.let {
                    position = Vector2(it[0], it[1])
                    // a third number is the depth
                    it.getOrNull(2)?.let { z -> Depth.set(this, z) }
                }

            // the depth as files had it before `pos` had a third number
            Depth.ATTRIBUTE -> r.toDoubleOrNull()?.let { Depth.set(this, it) }
            else -> attributes.other[l] = r
        }
    }

    /** The node as a file has it; [at] is where it is written to be, when that is not where it is now. */
    fun retrieveAttributes(at: Vector3 = point): Map<String, String> {
        // the content type of this level is written where it always was, among the other attributes
        val here = (detail().attributes() - NodeType.ATTRIBUTE).mapKeys { Detail.written(it.key, level) }
        val other = attributes.other.filterNotNull()
            .mapKeys { if (it.key == NodeType.ATTRIBUTE) Detail.written(it.key, level) else it.key }
        val elsewhere = aside.keys.sorted().flatMap { at -> aside.getValue(at).attributes().map { Detail.written(it.key, at) to it.value } }
        return here + mapOf(
            "marks" to attributes.marks.takeIf { it.isNotEmpty() },
            // the depth is the third number, for a node that has one
            "pos" to (listOf(at.x, at.y) + listOfNotNull(at.z.takeIf { it != 0.0 })).joinToString(",") { it.toNiceString() } + "!",
        ).filterNotNull() + other + elsewhere
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